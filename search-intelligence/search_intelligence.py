from fastapi import FastAPI, UploadFile, File, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
from FlagEmbedding import FlagReranker
from dotenv import load_dotenv
import os
import torch
import numpy as np
import gc
import easyocr
import cv2
from pdf2image import convert_from_bytes

load_dotenv()

app = FastAPI()

# MPS 사용 가능 여부 확인 및 설정
device = "cpu"
if torch.backends.mps.is_available():
    device = "mps"
elif torch.cuda.is_available(): # CUDA(NVIDIA GPU)도 확인
    device = "cuda"

print(f"Using device: {device}")

# 0. OCR Model Load (EasyOCR)
# GPU 사용 여부: device가 cpu가 아니면 True
use_gpu = (device != "cpu")
print(f"Loading EasyOCR Model (gpu={use_gpu})...")
# 한국어('ko'), 영어('en') 지원
reader = easyocr.Reader(['ko', 'en'], gpu=use_gpu)

# 1. Embedding Model Load
embed_model = SentenceTransformer(os.getenv('EMBEDDING_MODEL_NAME', 'jhgan/ko-sroberta-sts'), device=device)

# 2. Reranking Model Load
# use_fp16=True is only beneficial for CUDA
use_fp16 = (device == "cuda")
reranker_model_name = os.getenv('RERANKER_MODEL_NAME', 'BAAI/bge-reranker-base')
print(f"Loading Reranker Model: {reranker_model_name} (fp16={use_fp16})")

# FlagReranker는 내부적으로 device를 자동 감지하거나 설정할 수 있음.
# BAAI/bge-reranker-v2-m3
reranker = FlagReranker(
    reranker_model_name,
    use_fp16=use_fp16,
    trust_remote_code=True
)

class TextRequest(BaseModel):
    text: str

class TextsRequest(BaseModel):
    texts: list[str]

class RerankRequest(BaseModel):
    query: str
    documents: list[str]

@app.post("/embed")
async def embed_text(request: TextRequest):
    embedding = embed_model.encode(request.text).tolist()
    return {"embedding": embedding}

@app.post("/embed/batch")
async def embed_texts(request: TextsRequest):
    import time
    start_time = time.time()

    safe_batch_size = 8 # 메모리 부족 방지를 위해 32 -> 8로 축소

    # batch_size를 요청 크기에 맞추거나 고정값 사용 (메모리에 따라 조정)
    # convert_to_tensor=False -> numpy array 반환 (JSON 직렬화 위해 list 변환 필요하므로 tensor 불필요)
    try:
        embeddings = embed_model.encode(
            request.texts,
            batch_size=safe_batch_size,
            show_progress_bar=False,
            normalize_embeddings=True # 검색용이라면 정규화 추천
        ).tolist()
    finally:
        # 명시적 메모리 해제
        gc.collect()
        if device == "mps":
            torch.mps.empty_cache()
        elif device == "cuda":
            torch.cuda.empty_cache()
    
    elapsed = time.time() - start_time
    print(f"[Batch] {len(request.texts)}건 처리 소요 시간: {elapsed:.4f}초")
    
    return {"embeddings": embeddings}

@app.post("/rerank")
async def rerank_documents(request: RerankRequest):
    if not request.documents:
        return {"scores": [], "indices": []}

    # (query, document) 쌍 생성
    pairs = [[request.query, doc] for doc in request.documents]
    
    # 점수 계산
    # compute_score는 리스트 형태의 점수를 반환 (예: [0.1, 0.9, -0.5])
    # batch_size 조절 가능
    try:
        scores = reranker.compute_score(pairs, batch_size=16) # 리랭킹 배치 사이즈 4 -> 16으로 증가
    finally:
        # 리랭킹 후에도 메모리 해제
        gc.collect()
        if device == "mps":
            torch.mps.empty_cache()
        elif device == "cuda":
            torch.cuda.empty_cache()
    
    # 단건일 경우 float 반환될 수 있으므로 리스트로 변환
    if isinstance(scores, float):
        scores = [scores]
    
    # 점수 내림차순으로 인덱스 정렬
    # numpy argsort는 오름차순이므로 [::-1]로 뒤집음
    indices = np.argsort(scores)[::-1].tolist()
    
    # 점수도 Python list로 변환 (JSON 직렬화)
    scores_list = [float(s) for s in scores]
    
    return {
        "scores": scores_list,
        "indices": indices
    }

@app.post("/ocr")
async def ocr_image(file: UploadFile = File(...)):
    """
    이미지 또는 PDF 파일을 업로드 받아 텍스트를 추출합니다.
    """
    try:
        filename = file.filename.lower()
        contents = await file.read()
        extracted_texts = []

        # 1. PDF 처리
        if filename.endswith(".pdf"):
            try:
                # PDF를 이미지 리스트로 변환 (기본 200dpi)
                images = convert_from_bytes(contents)
                print(f"PDF 변환됨: {len(images)} 페이지")
                
                for i, image in enumerate(images):
                    # PIL Image -> NumPy array (OpenCV format)
                    img_np = np.array(image)
                    
                    # EasyOCR 수행
                    result = reader.readtext(img_np, detail=0, paragraph=True)
                    page_text = "\n".join(result)
                    extracted_texts.append(page_text)
                    print(f" - {i+1}페이지 OCR 완료")
                    
            except Exception as e:
                print(f"PDF 처리 실패 (poppler가 설치되었는지 확인하세요): {e}")
                raise HTTPException(status_code=500, detail=f"PDF 처리 실패: {str(e)}")

        # 2. 일반 이미지 처리
        else:
            # 바이트를 numpy 배열로 변환
            nparr = np.frombuffer(contents, np.uint8)
            # 이미지를 OpenCV 포맷으로 디코딩
            img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            
            if img is None:
                raise HTTPException(status_code=400, detail="이미지 파일을 디코딩할 수 없습니다.")

            # OCR 수행
            result = reader.readtext(img, detail=0, paragraph=True)
            extracted_texts.append("\n".join(result))
        
        # 결과 텍스트 결합
        full_text = "\n\n".join(extracted_texts)
        return {"text": full_text}
        
    except Exception as e:
        print(f"OCR 처리 중 오류 발생: {str(e)}")
        raise HTTPException(status_code=500, detail=f"OCR 처리 실패: {str(e)}")