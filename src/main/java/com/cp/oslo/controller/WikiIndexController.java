package com.cp.oslo.controller;

import com.cp.oslo.config.IndexRegistry;
import com.cp.oslo.config.WikiDataSource;
import com.cp.oslo.domain.IndexState;
import com.cp.oslo.repository.IndexStateRepository;
import com.cp.oslo.service.IndexingService;
import com.cp.oslo.service.OpenSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM 위키 항목 인덱스(wiki) 관리 컨트롤러
 * 원본은 contact-intelligence store DB의 tb_wiki_item이며 항목 단위로 색인한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wiki-index")
@RequiredArgsConstructor
public class WikiIndexController {

    private static final String INDEX_NAME = "wiki";

    private final IndexingService indexingService;
    private final IndexRegistry indexRegistry;
    private final OpenSearchService openSearchService;
    private final IndexStateRepository indexStateRepository;
    private final WikiDataSource wikiDataSource;

    /**
     * GET /api/v1/wiki-index/info
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        var definition = indexRegistry.get(INDEX_NAME);
        if (definition == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> info = new HashMap<>();
        info.put("indexName", INDEX_NAME);
        info.put("sourceTable", definition.getSourceTableName());
        info.put("description", definition.getDescription());
        info.put("exists", openSearchService.indexExists(INDEX_NAME));
        info.put("sourceAvailable", wikiDataSource.isAvailable());
        info.put("fieldMappingsCount", definition.getFields().size());

        IndexState state = indexStateRepository.findById(INDEX_NAME).orElse(null);
        if (state != null) {
            info.put("lastSyncTime", state.getLastSyncTime());
            info.put("lastSyncStatus", state.getLastSyncStatus());
        }

        info.put("syncUrl", "/api/v1/wiki-index/sync");
        info.put("searchUrl", "/api/v1/search/hybrid/wiki?query=키워드");
        return ResponseEntity.ok(info);
    }

    /**
     * POST /api/v1/wiki-index/create-index — sync와 동일(항상 삭제 후 재생성)
     */
    @PostMapping("/create-index")
    public ResponseEntity<Map<String, Object>> createIndex() {
        return sync();
    }

    /**
     * POST /api/v1/wiki-index/sync
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync() {
        log.info("wiki 인덱스 동기화 요청");
        Map<String, Object> result = new HashMap<>();
        if (!wikiDataSource.isAvailable()) {
            result.put("success", false);
            result.put("message", "wiki.datasource.url이 설정되지 않아 동기화할 수 없습니다.");
            return ResponseEntity.badRequest().body(result);
        }
        try {
            var history = indexingService.syncIndex(INDEX_NAME);
            result.put("success", history != null);
            result.put("message", history != null ? "동기화 완료." : "인덱스가 비활성화되어 있습니다.");
            if (history != null) {
                result.put("status", history.getStatus());
                result.put("recordsProcessed", history.getRecordsProcessed());
                result.put("recordsFailed", history.getRecordsFailed());
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("wiki 동기화 중 오류 발생", e);
            result.put("success", false);
            result.put("message", "동기화 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * DELETE /api/v1/wiki-index
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteIndex() {
        log.info("wiki 인덱스 삭제 요청");
        Map<String, Object> result = new HashMap<>();
        try {
            boolean deleted = openSearchService.deleteIndex(INDEX_NAME);
            result.put("success", deleted);
            result.put("message", deleted ? "wiki 인덱스 삭제 완료." : "wiki 인덱스가 존재하지 않거나 삭제 실패.");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("wiki 인덱스 삭제 중 오류 발생", e);
            result.put("success", false);
            result.put("message", "wiki 인덱스 삭제 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }
}
