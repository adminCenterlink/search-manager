package com.cp.havana.search;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class SearchEngineIndexingApi {

    private final String baseUrl;

    public SearchEngineIndexingApi(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be null or blank");
        }

        // URL 중복 문제 방지
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    public void searchEngineIndexingApiRequest(String type, String indexName, String uuid) {

        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be null or blank");
        }
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("uuid must not be null or blank");
        }

        String normalizedType = type == null ? "" : type.toLowerCase();
        boolean sync = "sync".equals(normalizedType);

        String targetUrl = String.format("%s/sync/%s/%s", baseUrl, indexName, uuid);

        log.info("SearchEngine API Request: url={}, type={}", targetUrl, normalizedType);

        try {
            HttpResponse<String> response =
                    sync ? Unirest.post(targetUrl).asString()
                            : Unirest.delete(targetUrl).asString();

            if (!response.isSuccess()) {
                log.error("SearchEngine request failed: status={}, body={}",
                        response.getStatus(), response.getBody());

                throw new SearchEngineException(
                        "검색 서버 오류: status=" + response.getStatus() +
                                ", body=" + response.getBody()
                );
            }

            log.info("SearchEngine request success (status={})", response.getStatus());

        } catch (UnirestException e) {
            log.error("SearchEngine HTTP 호출 실패: url={}", targetUrl, e);
            throw new SearchEngineException("검색 서버 HTTP 호출 실패", e);
        }
    }

    public void fileDeleteIndexingApiRequest(String uuid) {
        String targetUrl = String.format("%s/%s/%s", baseUrl, "file-index", uuid);
        try {
            HttpResponse<String> response = Unirest.delete(targetUrl).asString();
            if (!response.isSuccess()) {
                log.error("SearchEngine request failed: status={}, body={}", response.getStatus(), response.getBody());

                throw new SearchEngineException(
                        "검색 서버 오류: status=" + response.getStatus() +
                                ", body=" + response.getBody()
                );
            }

            log.info("SearchEngine request success (status={})", response.getStatus());

        } catch (UnirestException e) {
            log.error("SearchEngine HTTP 호출 실패: url={}", targetUrl, e);
            throw new SearchEngineException("검색 서버 HTTP 호출 실패", e);
        }
    }

    // 헬퍼 메서드 (서비스 코드에서 더 깔끔하게 사용 가능)
    public void sync(String indexName, String uuid) {
        searchEngineIndexingApiRequest("sync", indexName, uuid);
    }

    public void delete(String indexName, String uuid) {
        searchEngineIndexingApiRequest("delete", indexName, uuid);
    }
}