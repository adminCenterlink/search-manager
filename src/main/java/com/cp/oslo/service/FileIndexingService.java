package com.cp.oslo.service;

import com.cp.oslo.client.SearchIntelligenceClient;
import com.cp.oslo.config.IndexRegistry;
import com.cp.oslo.config.SearchIndexProperties; // SearchIndexProperties import 추가
import com.cp.oslo.domain.TbFile;
import com.cp.oslo.repository.TbFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate; // 추가: JdbcTemplate import

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors; // 추가
import java.util.Collections; // 추가

@Service
@RequiredArgsConstructor
@Slf4j
public class FileIndexingService {

    private final TbFileRepository tbFileRepository;
    private final OpenSearchService openSearchService;
    private final SearchIntelligenceClient searchIntelligenceClient;
    private final IndexRegistry indexRegistry;
    private final SearchIndexProperties searchIndexProperties;
    private final JdbcTemplate jdbcTemplate; // JdbcTemplate 주입 // SearchIndexProperties 주입

    // 파일 읽기 뿌리는 로그 경로와 분리한다(파일은 NFS 마운트, 로그는 로컬).
    @Value("${UPLOAD_PATH:/app/upload}")
    private String containerUploadPath;

    @Value("${search.indexes.file.ocr-enabled:false}")
    private boolean ocrEnabled;

    @Value("#{'${search.indexes.file.target-src-ids:CALL,MANUAL,NOTICE}'.split(',')}")
    private List<String> targetSrcIds;
    
    private static final List<String> SUPPORTED_CONTENT_TYPES = List.of(
            "application/pdf",
            // PPT
            "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.presentation",
            // Excel
            "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            // Text/CSV
            "text/csv", "text/plain",
            // Word
            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text", "application/rtf",
            // HWP
            "application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp", "application/hwp+zip",
            // Images
            "image/png", "image/jpeg", "image/gif", "image/bmp", "image/tiff",
            // Etc
            "application/octet-stream"
    );

    @Transactional(readOnly = true)
    public void indexAllFiles() {
        log.info("파일 전체 인덱싱 시작");

        // 1. 인덱스 생성 (없으면)
        boolean indexExists = openSearchService.indexExists("file");
        log.debug("인덱스 'file' 존재 여부: {}", indexExists);
        if (!indexExists) {
            openSearchService.createIndex(indexRegistry.get("file"));
        }

        // 2. 활성화된 파일 타입 확인 (Yaml 설정)
        if (targetSrcIds == null || targetSrcIds.isEmpty()) {
            log.warn("파일 인덱싱 대상 타입(target-src-ids)이 설정되지 않았습니다. 파일 인덱싱을 건너뜝니다.");
            return;
        }
        log.info("인덱싱 대상 파일 타입(SRC_ID1): {}", targetSrcIds);

        // 3. 파일 조회 (컨텐츠 타입 및 SRC_ID1 필터링)
        List<TbFile> files = tbFileRepository.findIndexableFilesByContentTypesAndSrcId1In(SUPPORTED_CONTENT_TYPES, targetSrcIds);
        log.info("인덱싱 대상 파일 수: {}", files.size());

        int success = 0;
        int fail = 0;

        for (TbFile file : files) {
            try {
                indexFile(file);
                success++;
            } catch (Exception e) {
                log.error("파일 인덱싱 실패 (ID: {}, Name: {}): {}", file.getFileId(), file.getFileNm(), e.getMessage());
                fail++;
            }
        }

        log.info("파일 인덱싱 완료. 성공: {}, 실패: {}", success, fail);
    }

    public void indexFileByUuid(String uuid) {
        TbFile file = tbFileRepository.findByFileUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 UUID의 파일을 찾을 수 없습니다: " + uuid));
        
        try {
            // 인덱스가 없으면 생성
            if (!openSearchService.indexExists("file")) {
                openSearchService.createIndex(indexRegistry.get("file"));
            }
            indexFile(file);
            log.info("단건 파일 인덱싱 완료: {}", uuid);
        } catch (Exception e) {
            log.error("단건 파일 인덱싱 실패: {}", uuid, e);
            throw new RuntimeException("파일 인덱싱 실패", e);
        }
    }

    private void indexFile(TbFile file) throws Exception {
        // 1. 실제 파일 경로 계산
        String localPathPrefix = searchIndexProperties.getIndexes().get("file").getLocalPathPrefix();
        String savedPath = file.getSavedFilePath();
        String realPath;
        
        log.info("인덱싱 처리 중: [파일경로] {}/{}", file.getSavedFilePath(), file.getSavedFileNm());
        
        if (savedPath.startsWith(localPathPrefix)) {
            realPath = containerUploadPath + savedPath.substring(localPathPrefix.length());
        } else {
            // localPathPrefix가 savedPath의 시작 부분과 일치하지 않는 경우, 경로 매핑 실패로 간주
            // 이 경우는 설정 오류일 가능성이 높으므로 명확하게 예외를 발생시키거나 로그를 남겨야 함.
            log.error("파일 경로 매핑 실패: savedPath '{}'가 localPathPrefix '{}'로 시작하지 않습니다.", savedPath, localPathPrefix);
            throw new IllegalArgumentException("파일 경로를 올바르게 매핑할 수 없습니다: " + savedPath);
        }

        File targetFile = new File(realPath, file.getSavedFileNm());

        if (!targetFile.exists()) {
            throw new java.io.FileNotFoundException("파일을 찾을 수 없습니다: " + targetFile.getAbsolutePath());
        }

        // 2. Tika Text Extraction
        String content = extractText(targetFile, file.getContentType());
        if (content == null || content.trim().isEmpty()) {
            log.warn("텍스트 추출 결과 없음: {}", file.getFileId());
            return;
        }

        // 3. Chunking
        List<String> chunks = chunkText(content, 1000, 200); // 1000자 청크, 200자 오버랩

        // 4. Embedding (Batch)
        List<List<Double>> allEmbeddings = new ArrayList<>();
        int batchSize = 50; // 임베딩 서비스에 한 번에 보낼 청크 개수
        
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            List<String> subChunks = chunks.subList(i, end);
            
            log.debug("배치 임베딩 요청: {} / {} (청크 {} ~ {})", i / batchSize + 1, (chunks.size() + batchSize - 1) / batchSize, i, end -1);
            List<List<Double>> subEmbeddings = searchIntelligenceClient.embedBatch(subChunks);

            if (subEmbeddings == null || subEmbeddings.size() != subChunks.size()) {
                // 서브 배치 임베딩 실패 시 전체 인덱싱 중단 또는 해당 파일 인덱싱 건너뛰기
                log.error("서브 배치 임베딩 결과 개수 불일치: chunks={}, embeddings={}", subChunks.size(), (subEmbeddings != null ? subEmbeddings.size() : "null"));
                throw new RuntimeException(String.format("임베딩 생성 결과가 청크 개수와 일치하지 않습니다. (파일 ID: %s)", file.getFileId()));
            }
            allEmbeddings.addAll(subEmbeddings);
        }

        // 전체 임베딩 개수 검증 (chunking 후 결과가 0개인 경우 대비)
        if (allEmbeddings.size() != chunks.size()) {
            log.error("최종 임베딩 개수 불일치: chunks={}, allEmbeddings={}", chunks.size(), allEmbeddings.size());
            throw new RuntimeException(String.format("최종 임베딩 생성 결과가 청크 개수와 일치하지 않습니다. (파일 ID: %s)", file.getFileId()));
        }
        List<List<Double>> embeddings = allEmbeddings; // 변수명 일치

        // 5. Build Document
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", file.getFileUuid()); // OpenSearch _id 매핑
        doc.put("FILE_ID", file.getFileId());
        doc.put("FILE_NM", file.getFileNm());
        doc.put("SAVED_FILE_PATH", file.getSavedFilePath());
        doc.put("FILE_UUID", file.getFileUuid());
        doc.put("URL", file.getUrl());
        doc.put("CONTENT_TYPE", file.getContentType());
        doc.put("REG_DT", file.getRegDt());

        List<Map<String, Object>> paragraphs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            List<Double> embedding = embeddings.get(i);

            // embedding 유효성 검사
            boolean isValid = embedding != null && !embedding.isEmpty();
            if (isValid) {
                for (Double val : embedding) {
                    if (val == null) {
                        isValid = false;
                        break;
                    }
                }
            }

            // 유효하지 않은 embedding이 있는 청크는 아예 추가하지 않음
            if (!isValid) {
                log.warn("청크 {}의 임베딩이 유효하지 않습니다 (null 또는 비어있음). 해당 청크를 건너뜁니다.", i);
                continue;
            }

            // 유효한 경우에만 paragraph 추가
            Map<String, Object> p = new HashMap<>();
            p.put("content", chunks.get(i));
            p.put("embedding", embedding);
            paragraphs.add(p);
        }

        // 유효한 paragraph가 하나도 없으면 오류 처리
        if (paragraphs.isEmpty()) {
            log.error("파일 {}에 대한 유효한 임베딩이 생성되지 않았습니다", file.getFileId());
            throw new RuntimeException("유효한 임베딩이 생성되지 않았습니다.");
        }
        doc.put("paragraphs", paragraphs);



        // 6. Index
        openSearchService.indexDocument("file", doc);
    }

    private String extractText(File file, String contentType) throws Exception {
        String fileName = file.getName(); // 로깅용
        
        // 1. 이미지 파일이면 바로 OCR (활성화된 경우만)
        if (ocrEnabled && isImageContentType(contentType)) {
            return performOcr(file, contentType);
        }

        // 2. Tika (기본 텍스트 추출)
        String content = "";
        try {
            content = extractTextWithTika(file);
        } catch (Exception e) {
            log.warn("Tika 텍스트 추출 실패 (파일: {}): {}", fileName, e.getMessage());
        }

        // 3. 텍스트가 없고 PDF인 경우 OCR 시도 (활성화된 경우만)
        if (ocrEnabled && (content == null || content.trim().isEmpty()) && "application/pdf".equalsIgnoreCase(contentType)) {
            log.info("PDF 텍스트 추출 결과 없음. OCR 시도: {}", fileName);
            return performOcr(file, contentType);
        }
        
        return content;
    }

    private boolean isImageContentType(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.startsWith("image/") && !lower.equals("image/svg+xml"); // SVG는 OCR 제외 등 정책 필요 시 조정
    }

    private String performOcr(File file, String contentType) {
        try {
            log.info("OCR 요청: {}", file.getName());
            String ocrText = searchIntelligenceClient.ocr(file, contentType);
            if (ocrText == null || ocrText.trim().isEmpty()) {
                log.warn("OCR 결과가 비어있습니다: {}", file.getName());
                return "";
            }

            // 추출된 텍스트가 3글자 이상인 경우에만 반환
            if (ocrText.trim().length() <= 2) {
                log.warn("OCR 추출 결과가 너무 짧아(2자 이하) 색인에서 제외합니다 (결과: '{}'): {}", ocrText.trim(), file.getName());
                return "";
            }

            return ocrText;
        } catch (Exception e) {
            log.error("OCR 처리 중 오류 발생: {}", file.getName(), e);
            return "";
        }
    }

    private String extractTextWithTika(File file) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(-1); // Unlimited size
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        try (InputStream stream = new FileInputStream(file)) {
            parser.parse(stream, handler, metadata);
            return handler.toString();
        }
    }

    private List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        int len = text.length();
        int start = 0;

        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            chunks.add(text.substring(start, end));

            if (end == len) {
                break;
            }
            start += (chunkSize - overlap);
            // 다음 시작점이 현재 끝점을 넘어설 경우, 현재 끝점에서 시작하도록 조정
            // 이는 다음 청크가 반드시 생성되도록 보장합니다.
            if (start >= end) {
                start = end - overlap;
                if (start < 0) start = 0; // 시작점이 음수가 되지 않도록 방지
            }
        }

        return chunks;
    }

    public com.cp.oslo.dto.SearchResultDto search(String query, Double textWeight, Integer size) {
        List<Double> vector = searchIntelligenceClient.embed(query);
        return openSearchService.searchNestedHybrid("file", "paragraphs", "content", "embedding", query, vector, textWeight, size);
    }

    public com.cp.oslo.dto.SearchResultDto searchText(String query, Integer size) {
        return openSearchService.searchNestedText("file", "paragraphs", "content", query, size);
    }

    public boolean deleteIndex() {
        return openSearchService.deleteIndex("file");
    }
}
