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
from transformers import AutoTokenizer, AutoModelForSequenceClassification
import onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
import time
import logging
from logging.handlers import TimedRotatingFileHandler

load_dotenv()

# 로그 설정: 일별 로테이션, 30일 보관
LOG_DIR = os.getenv("LOG_DIR", os.path.join(os.path.dirname(__file__) or ".", "logs"))
os.makedirs(LOG_DIR, exist_ok=True)

logger = logging.getLogger("search_intelligence")
logger.setLevel(logging.INFO)

# 파일 핸들러: 매일 자정에 로테이션, 30일 보관
file_handler = TimedRotatingFileHandler(
    filename=os.path.join(LOG_DIR, "search_intelligence.log"),
    when="midnight",
    interval=1,
    backupCount=30,
    encoding="utf-8"
)
file_handler.suffix = "%Y-%m-%d"
file_handler.setFormatter(logging.Formatter(
    "%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
))

# 콘솔 핸들러
console_handler = logging.StreamHandler()
console_handler.setFormatter(logging.Formatter(
    "%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
))

logger.addHandler(file_handler)
logger.addHandler(console_handler)

# uvicorn access log도 같은 파일로
uvicorn_access = logging.getLogger("uvicorn.access")
uvicorn_access.addHandler(file_handler)
uvicorn_error = logging.getLogger("uvicorn.error")
uvicorn_error.addHandler(file_handler)

app = FastAPI()

# 디바이스 자동 감지: CUDA > MPS > CPU
device = "cpu"
if torch.cuda.is_available():
    device = "cuda"
elif torch.backends.mps.is_available():
    device = "mps"

logger.info(f"Using device: {device}")

# 0. OCR Model Load (EasyOCR)
# GPU 사용 여부: device가 cpu가 아니면 True
use_gpu = (device != "cpu")
logger.info(f"Loading EasyOCR Model (gpu={use_gpu})...")
# 한국어('ko'), 영어('en') 지원
reader = easyocr.Reader(['ko', 'en'], gpu=use_gpu)

# 1. Embedding Model Load
embed_model = SentenceTransformer(os.getenv('EMBEDDING_MODEL_NAME', 'jhgan/ko-sroberta-sts'), device=device)

# 2. Reranking Model Load
# GPU 사용 가능하면 FlagReranker(PyTorch), CPU만 가능하면 ONNX INT8로 자동 전환
reranker_model_name = os.getenv('RERANKER_MODEL_NAME', 'BAAI/bge-reranker-base')
onnx_env = os.getenv('USE_ONNX_RERANKER', 'auto').lower()
if onnx_env == 'auto':
    use_onnx = (device == "cpu")  # CPU일 때만 ONNX 사용
else:
    use_onnx = (onnx_env == 'true')

def export_reranker_to_onnx(model_name, onnx_path):
    """PyTorch 모델을 ONNX로 수동 변환"""
    logger.info(f"ONNX 변환 시작: {model_name} -> {onnx_path}")
    pt_model = AutoModelForSequenceClassification.from_pretrained(
        model_name, trust_remote_code=True
    )
    pt_model.eval()
    tokenizer = AutoTokenizer.from_pretrained(model_name, trust_remote_code=True)

    dummy_input = tokenizer(
        [["query", "document"]], padding=True, truncation=True,
        max_length=512, return_tensors="pt"
    )

    torch.onnx.export(
        pt_model,
        (dummy_input["input_ids"], dummy_input["attention_mask"]),
        onnx_path,
        input_names=["input_ids", "attention_mask"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "seq"},
            "attention_mask": {0: "batch", 1: "seq"},
            "logits": {0: "batch"},
        },
        opset_version=14,
        dynamo=False,  # 레거시 모드: INT8 양자화 호환
    )
    del pt_model
    gc.collect()
    logger.info(f"ONNX 변환 완료: {onnx_path}")

if use_onnx:
    onnx_dir = os.path.join(os.path.dirname(__file__) or ".", "onnx_models")
    os.makedirs(onnx_dir, exist_ok=True)
    onnx_fp32_path = os.path.join(onnx_dir, "reranker.onnx")
    onnx_int8_path = os.path.join(onnx_dir, "reranker_int8.onnx")

    # 1) FP32 ONNX 변환
    if not os.path.exists(onnx_fp32_path):
        export_reranker_to_onnx(reranker_model_name, onnx_fp32_path)

    # 2) INT8 동적 양자화 (CPU 최적화 핵심)
    if not os.path.exists(onnx_int8_path):
        logger.info("INT8 양자화 시작...")
        quantize_dynamic(
            onnx_fp32_path,
            onnx_int8_path,
            weight_type=QuantType.QInt8,
        )
        logger.info("INT8 양자화 완료")

    logger.info(f"Loading Reranker Model (ONNX INT8): {onnx_int8_path}")
    reranker_tokenizer = AutoTokenizer.from_pretrained(reranker_model_name, trust_remote_code=True)

    # ONNX Runtime 세션 최적화
    sess_options = ort.SessionOptions()
    sess_options.inter_op_num_threads = os.cpu_count()
    sess_options.intra_op_num_threads = os.cpu_count()
    sess_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

    reranker_session = ort.InferenceSession(onnx_int8_path, sess_options, providers=["CPUExecutionProvider"])
    reranker = None
    logger.info("ONNX INT8 Reranker 로드 완료")
else:
    logger.info(f"Loading Reranker Model (FlagReranker): {reranker_model_name}")
    use_fp16 = (device == "cuda")
    reranker = FlagReranker(
        reranker_model_name,
        use_fp16=use_fp16,
        trust_remote_code=True
    )
    reranker_tokenizer = None
    reranker_session = None

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
    logger.info(f"[Batch] {len(request.texts)}건 처리 소요 시간: {elapsed:.4f}초")
    
    return {"embeddings": embeddings}

@app.post("/rerank")
async def rerank_documents(request: RerankRequest):
    if not request.documents:
        return {"scores": [], "indices": []}

    start_time = time.time()

    # (query, document) 쌍 생성
    pairs = [[request.query, doc] for doc in request.documents]

    if use_onnx and reranker_session is not None:
        # ONNX Runtime 추론: 배치 단위로 처리
        batch_size = 8
        all_scores = []
        for i in range(0, len(pairs), batch_size):
            batch = pairs[i:i+batch_size]
            inputs = reranker_tokenizer(
                batch, padding=True, truncation=True,
                max_length=512, return_tensors="np"  # numpy로 직접 변환
            )
            logits = reranker_session.run(
                ["logits"],
                {
                    "input_ids": inputs["input_ids"],
                    "attention_mask": inputs["attention_mask"],
                }
            )[0]
            # logits shape: (batch, 1) 또는 (batch,)
            batch_scores = logits.squeeze(-1).tolist()
            if isinstance(batch_scores, float):
                batch_scores = [batch_scores]
            all_scores.extend(batch_scores)
        scores = all_scores
    else:
        # FlagReranker 폴백
        try:
            scores = reranker.compute_score(pairs, batch_size=16)
        finally:
            gc.collect()
            if device == "mps":
                torch.mps.empty_cache()
            elif device == "cuda":
                torch.cuda.empty_cache()

    # 단건일 경우 float 반환될 수 있으므로 리스트로 변환
    if isinstance(scores, float):
        scores = [scores]

    # 점수 내림차순으로 인덱스 정렬
    indices = np.argsort(scores)[::-1].tolist()
    scores_list = [float(s) for s in scores]

    elapsed = time.time() - start_time
    logger.info(f"[Rerank] {len(request.documents)}건 리랭킹 소요 시간: {elapsed:.4f}초 (ONNX={use_onnx})")

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
                logger.info(f"PDF 변환됨: {len(images)} 페이지")
                
                for i, image in enumerate(images):
                    # PIL Image -> NumPy array (OpenCV format)
                    img_np = np.array(image)
                    
                    # EasyOCR 수행
                    result = reader.readtext(img_np, detail=0, paragraph=True)
                    page_text = "\n".join(result)
                    extracted_texts.append(page_text)
                    logger.info(f" - {i+1}페이지 OCR 완료")
                    
            except Exception as e:
                logger.error(f"PDF 처리 실패 (poppler가 설치되었는지 확인하세요): {e}")
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
        logger.error(f"OCR 처리 중 오류 발생: {str(e)}")
        raise HTTPException(status_code=500, detail=f"OCR 처리 실패: {str(e)}")