package com.cp.oslo.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 임베딩 서비스와 통신하는 클라이언트
 */
@Component
@Slf4j
public class SearchIntelligenceClient {

    private final WebClient webClient;

    public SearchIntelligenceClient(@Value("${search-intelligence.url:http://localhost:8000}") String searchIntelligenceUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(searchIntelligenceUrl)
                .exchangeStrategies(org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 16MB
                        .build())
                .build();
        log.info("SearchIntelligenceClient 초기화: {}", searchIntelligenceUrl);
    }

    /**
     * 이미지 파일 OCR 요청 (파일 전송 방식)
     */
    public String ocr(File file, String contentType) {
        try {
            log.debug("OCR 요청: 파일={}, 크기={} bytes, 타입={}", file.getName(), file.length(), contentType);

            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            
            // 파일명 보정: PDF인데 확장자가 없으면 .pdf 추가
            String filename = file.getName();
            if ("application/pdf".equalsIgnoreCase(contentType) && !filename.toLowerCase().endsWith(".pdf")) {
                filename += ".pdf";
                log.debug("PDF 파일명 확장자 보정 전송: {}", filename);
            }
            
            builder.part("file", new FileSystemResource(file))
                   .filename(filename); // 보정된 파일명 설정

            Map<String, Object> response = webClient.post()
                    .uri("/search/ocr")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response == null) {
                throw new RuntimeException("OCR 서비스 응답이 null입니다");
            }

            String text = (String) response.get("text");
            if (text == null) {
                log.warn("OCR 결과 텍스트가 없습니다.");
                return "";
            }

            log.debug("OCR 완료: 텍스트 길이={}", text.length());
            return text;

        } catch (Exception e) {
            log.error("OCR 요청 실패: {}", file.getName(), e);
            // OCR 실패 시 예외를 던지지 않고 빈 문자열 반환 (필요 시 정책 변경)
            throw new RuntimeException("OCR 요청 실패", e);
        }
    }

    /**
     * 단일 텍스트의 임베딩 벡터 생성
     */
    public List<Double> embed(String text) {
        try {
            Map<String, String> request = Map.of("text", text);

            log.debug("임베딩 요청: {}", text);

            Map<String, Object> response = webClient.post()
                    .uri("/search/embed")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response == null) {
                throw new RuntimeException("임베딩 서비스 응답이 null입니다");
            }

            // JSON 파싱 시 숫자는 Double로 변환됨
            List<?> embeddingList = (List<?>) response.get("embedding");
            if (embeddingList == null) {
                throw new RuntimeException("임베딩 응답에 'embedding' 필드가 없습니다");
            }

            List<Double> embedding = embeddingList.stream()
                    .map(obj -> {
                        if (obj instanceof Double) {
                            return (Double) obj;
                        } else if (obj instanceof Float) {
                            return ((Float) obj).doubleValue();
                        } else if (obj instanceof Number) {
                            return ((Number) obj).doubleValue();
                        }
                        throw new IllegalArgumentException("Invalid embedding type: " + obj.getClass());
                    })
                    .toList();

            log.debug("임베딩 생성 완료: dimension={}", embedding.size());
            return embedding;

        } catch (Exception e) {
            log.error("임베딩 생성 실패: {}", text, e);
            throw new RuntimeException("임베딩 생성 실패", e);
        }
    }

    /**
     * 배치 텍스트의 임베딩 벡터 생성
     */
    public List<List<Double>> embedBatch(List<String> texts) {
        try {
            Map<String, List<String>> request = Map.of("texts", texts);

            log.debug("배치 임베딩 요청: {} 개 문서", texts.size());

            Map<String, Object> response = webClient.post()
                    .uri("/search/embed/batch")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response == null) {
                throw new RuntimeException("임베딩 서비스 응답이 null입니다");
            }

            List<?> embeddingsList = (List<?>) response.get("embeddings");
            if (embeddingsList == null) {
                throw new RuntimeException("임베딩 응답에 'embeddings' 필드가 없습니다");
            }

            List<List<Double>> embeddings = embeddingsList.stream()
                    .map(obj -> {
                        List<?> embeddingList = (List<?>) obj;
                        return embeddingList.stream()
                                .map(num -> {
                                    if (num instanceof Double) {
                                        return (Double) num;
                                    } else if (num instanceof Number) {
                                        return ((Number) num).doubleValue();
                                    }
                                    throw new IllegalArgumentException("Invalid embedding type: " + num.getClass());
                                })
                                .toList();
                    })
                    .toList();

            log.debug("배치 임베딩 생성 완료: {} 개 문서", embeddings.size());
            return embeddings;

        } catch (Exception e) {
            log.error("배치 임베딩 생성 실패", e);
            throw new RuntimeException("배치 임베딩 생성 실패", e);
        }
    }

    /**
     * 리랭킹 요청 (쿼리 + 문서 리스트 -> 점수 및 정렬된 인덱스 반환)
     */
    public Map<String, Object> rerank(String query, List<String> documents) {
        try {
            Map<String, Object> request = Map.of(
                    "query", query,
                    "documents", documents
            );

            log.debug("리랭킹 요청: query={}, docs={}", query, documents.size());

            Map<String, Object> response = webClient.post()
                    .uri("/search/rerank")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response == null) {
                throw new RuntimeException("리랭킹 서비스 응답이 null입니다");
            }
            return response;

        } catch (Exception e) {
            log.error("리랭킹 요청 실패", e);
            throw new RuntimeException("리랭킹 요청 실패", e);
        }
    }

    /**
     * 임베딩 서비스 연결 확인
     */
    public boolean isAvailable() {
        try {
            webClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("임베딩 서비스 연결 성공");
            return true;

        } catch (Exception e) {
            log.warn("임베딩 서비스 연결 실패: {}", e.getMessage());
            return false;
        }
    }
}
