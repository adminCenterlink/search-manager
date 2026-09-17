package com.cp.oslo.service;

import com.cp.oslo.client.SearchIntelligenceClient;
import com.cp.oslo.config.IndexRegistry;
import com.cp.oslo.config.SearchIndexProperties;
import com.cp.oslo.config.VectorFieldConfig;
import com.cp.oslo.config.WikiDataSource;
import com.cp.oslo.domain.IndexState;
import com.cp.oslo.domain.SyncHistory;
import com.cp.oslo.model.FieldDefinition;
import com.cp.oslo.model.IndexDefinition;
import com.cp.oslo.repository.IndexStateRepository;
import com.cp.oslo.repository.SyncHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 데이터베이스 테이블을 OpenSearch로 인덱싱하는 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {

    private final IndexRegistry indexRegistry;
    private final SearchIndexProperties indexProperties;
    
    private final IndexStateRepository indexStateRepository;
    private final SyncHistoryRepository syncHistoryRepository;
    
    private final OpenSearchService openSearchService;
    private final JdbcTemplate jdbcTemplate;
    private final SearchIntelligenceClient searchIntelligenceClient;
    private final VectorFieldConfig vectorFieldConfig;
    private final FileIndexingService fileIndexingService;
    private final WikiDataSource wikiDataSource;

    private static final int BATCH_SIZE = 500;

    // 전량 재생성(삭제 후 재색인)하는 인덱스 — 원본이 행 단위 갱신 추적을 못 하는 것들
    private static final Set<String> HARD_RESET_INDEXES = Set.of("unified", "wiki");

    /** keyset paging 원본 — 마지막 행을 커서로 받아 다음 배치를 돌려준다. */
    @FunctionalInterface
    private interface BatchSource {
        List<Map<String, Object>> fetch(Map<String, Object> lastRow, int limit);
    }

    private record NamedSource(String name, BatchSource source) {}

    /**
     * 전체 동기화 실행 (인덱스 이름으로)
     */
    public SyncHistory syncIndex(String indexName) {
        // 1. 활성화 여부 확인 (YML 설정 기반)
        if (!isIndexEnabled(indexName)) {
            log.warn("인덱스가 비활성화되어 있어 동기화를 건너뜁니다: {}", indexName);
            return null;
        }

        // 2. File 인덱스 특수 처리
        if ("file".equalsIgnoreCase(indexName)) {
            return syncFileIndex();
        }

        // 3. wiki 인덱스는 store DB 접속이 없으면 건너뜀
        if ("wiki".equals(indexName) && !wikiDataSource.isAvailable()) {
            log.warn("wiki 인덱스 동기화 건너뜀: wiki.datasource.url이 설정되지 않았습니다.");
            return null;
        }

        // 4. 인덱스 정의 조회 (코드 기반)
        IndexDefinition definition = indexRegistry.get(indexName);
        if (definition == null) {
            throw new IllegalArgumentException("알 수 없는 인덱스입니다: " + indexName);
        }
        
        return executeSync(definition);
    }

    private SyncHistory syncFileIndex() {
        if (!isFileConfigEnabled()) {
            log.info("파일 인덱싱 건너뜀: 파일 인덱스가 비활성화 상태입니다.");
            return null;
        }

        log.info("========================================");
        log.info("동기화 시작: file");
        log.info("========================================");

        SyncHistory history = SyncHistory.builder()
                .indexName("file")
                .startTime(LocalDateTime.now())
                .status(SyncHistory.SyncStatus.RUNNING)
                .build();
        history = syncHistoryRepository.save(history);

        try {
            fileIndexingService.indexAllFiles();

            history.complete(SyncHistory.SyncStatus.SUCCESS, null);
            updateLastSyncState("file", SyncHistory.SyncStatus.SUCCESS);

            log.info("========================================");
            log.info("동기화 완료: file");
            log.info("========================================");

        } catch (Exception e) {
            log.error("========================================");
            log.error("동기화 실패: file", e);
            log.error("========================================");
            history.complete(SyncHistory.SyncStatus.FAILED, e.getMessage());
            updateLastSyncState("file", SyncHistory.SyncStatus.FAILED);
        }

        return syncHistoryRepository.save(history);
    }

    /**
     * 실제 동기화 로직 (Batch Processing 적용)
     */
    private SyncHistory executeSync(IndexDefinition definition) {
        log.info("========================================");
        log.info("동기화 시작: {} (테이블: {})", definition.getIndexName(), definition.getSourceTableName());
        log.info("========================================");

        SyncHistory history = SyncHistory.builder()
                .indexName(definition.getIndexName())
                .startTime(LocalDateTime.now())
                .status(SyncHistory.SyncStatus.RUNNING)
                .build();
        history = syncHistoryRepository.save(history);

        try {
            // 인덱스가 없으면 생성
            boolean isNewIndex = false;
            
            // unified/wiki 인덱스는 항상 삭제 후 재생성 (Hard Reset)하여 Clean 상태 유지
            if (HARD_RESET_INDEXES.contains(definition.getIndexName()) && openSearchService.indexExists(definition.getIndexName())) {
                log.info("{} 인덱스 초기화(삭제 후 재생성) 진행...", definition.getIndexName());
                openSearchService.deleteIndex(definition.getIndexName());
            }

            if (!openSearchService.indexExists(definition.getIndexName())) {
                log.info("인덱스 생성 중...");
                openSearchService.createIndex(definition);
                isNewIndex = true;
            }

            if (HARD_RESET_INDEXES.contains(definition.getIndexName())) {
                List<NamedSource> sources = "wiki".equals(definition.getIndexName())
                        ? List.of(new NamedSource("WIKI", this::fetchWikiBatch))
                        : resolveUnifiedSources();
                if (sources.isEmpty()) {
                    log.info("동기화할 원본이 없어 중단합니다: {}", definition.getIndexName());
                    return history; // 빈 상태로 종료
                }

                // Hard Reset 전략을 사용하므로 deleteDocumentsNotInTypes 호출 불필요
                // (항상 새 인덱스이므로 비활성 데이터가 존재할 수 없음)
                log.info("원본별 동기화를 시작합니다. (원본: {})", sources.stream().map(NamedSource::name).toList());

                long totalProcessed = 0;
                long successCount = 0;
                long failCount = 0;

                log.info("데이터 조회 및 인덱싱 시작 (Batch Size: {})", BATCH_SIZE);

                for (NamedSource named : sources) {
                    Map<String, Object> lastRow = null; // 원본별 keyset 커서(마지막 행)
                    long typeProcessed = 0;
                    log.info("----> 원본 동기화 시작: {}", named.name());

                    while (true) {
                        long loopStart = System.currentTimeMillis();

                        // 1. 배치 데이터 조회
                        List<Map<String, Object>> batch = named.source().fetch(lastRow, BATCH_SIZE);
                        long afterFetch = System.currentTimeMillis();

                        if (batch.isEmpty()) {
                            break;
                        }

                        // 다음 배치를 위한 커서 갱신 (임베딩 단계가 행을 제거할 수 있어 여기서 잡는다)
                        lastRow = new HashMap<>(batch.get(batch.size() - 1));

                        // 2. 임베딩 생성
                        enrichDocumentsWithEmbedding(definition.getIndexName(), batch);
                        long afterEmbedding = System.currentTimeMillis();

                        // 3. OpenSearch 인덱싱
                        OpenSearchService.BulkIndexResult result = openSearchService.bulkIndex(
                                definition.getIndexName(),
                                batch
                        );
                        long afterIndex = System.currentTimeMillis();

                        successCount += result.successCount();
                        failCount += result.failCount();
                        totalProcessed += batch.size();
                        typeProcessed += batch.size();

                        log.info("구간 소요시간 - DB조회: {}ms, 임베딩: {}ms, ES색인: {}ms | 총: {}ms",
                                (afterFetch - loopStart),
                                (afterEmbedding - afterFetch),
                                (afterIndex - afterEmbedding),
                                (afterIndex - loopStart));

                        log.info("진행 중... 처리: {}건 (현재 원본: {} | 총: {}건 | 성공: {} | 실패: {})",
                                typeProcessed, named.name(), totalProcessed, successCount, failCount);
                    }
                    log.info("<---- 원본 동기화 완료: {} (총 {}건)", named.name(), typeProcessed);
                }

                // 동기화 완료 처리
                history.setRecordsProcessed(totalProcessed);
                history.setRecordsSucceeded(successCount);
                history.setRecordsFailed(failCount);

                SyncHistory.SyncStatus status = failCount == 0
                        ? SyncHistory.SyncStatus.SUCCESS
                        : (successCount > 0 ? SyncHistory.SyncStatus.PARTIAL : SyncHistory.SyncStatus.FAILED);

                history.complete(status, null);
                updateLastSyncState(definition.getIndexName(), status);

                log.info("========================================");
                log.info("동기화 완료: {}", definition.getIndexName());
                log.info("총 처리: {}건 | 성공: {}건 | 실패: {}건 | 상태: {}",
                        totalProcessed, successCount, failCount, status);
                log.info("========================================");

            } else { // 그 외 인덱스는 기존 OFFSET 방식 유지
                long totalProcessed = 0;
                long successCount = 0;
                long failCount = 0;
                int offset = 0;
                
                log.info("데이터 조회 및 인덱싱 시작 (Batch Size: {})", BATCH_SIZE);

                while (true) {
                    long loopStart = System.currentTimeMillis();

                    // 1. 배치 데이터 조회
                    List<Map<String, Object>> batch = fetchBatchFromDatabase(definition, BATCH_SIZE, offset);
                    long afterFetch = System.currentTimeMillis();
                    
                    if (batch.isEmpty()) {
                        break;
                    }

                    // 2. 임베딩 생성
                    enrichDocumentsWithEmbedding(definition.getIndexName(), batch);
                    long afterEmbedding = System.currentTimeMillis();

                    // 3. OpenSearch 인덱싱
                    OpenSearchService.BulkIndexResult result = openSearchService.bulkIndex(
                            definition.getIndexName(),
                            batch
                    );
                    long afterIndex = System.currentTimeMillis();

                    successCount += result.successCount();
                    failCount += result.failCount();
                    totalProcessed += batch.size();
                    offset += BATCH_SIZE; // 다음 배치를 위해 오프셋 증가

                    log.info("구간 소요시간 - DB조회: {}ms, 임베딩: {}ms, ES색인: {}ms | 총: {}ms",
                            (afterFetch - loopStart),
                            (afterEmbedding - afterFetch),
                            (afterIndex - afterEmbedding),
                            (afterIndex - loopStart));

                    log.info("진행 중... 처리: {}건 | 성공: {} | 실패: {} (현재 오프셋: {})", 
                            totalProcessed, successCount, failCount, offset);
                }

                // 동기화 완료 처리
                history.setRecordsProcessed(totalProcessed);
                history.setRecordsSucceeded(successCount);
                history.setRecordsFailed(failCount);

                SyncHistory.SyncStatus status = failCount == 0
                        ? SyncHistory.SyncStatus.SUCCESS
                        : (successCount > 0 ? SyncHistory.SyncStatus.PARTIAL : SyncHistory.SyncStatus.FAILED);

                history.complete(status, null);
                updateLastSyncState(definition.getIndexName(), status);

                log.info("========================================");
                log.info("동기화 완료: {}", definition.getIndexName());
                log.info("총 처리: {}건 | 성공: {}건 | 실패: {}건 | 상태: {}", 
                        totalProcessed, successCount, failCount, status);
                log.info("========================================");
            }

        } catch (Exception e) {
            log.error("========================================");
            log.error("동기화 실패: {}", definition.getIndexName(), e);
            log.error("========================================");
            history.complete(SyncHistory.SyncStatus.FAILED, e.getMessage());
            updateLastSyncState(definition.getIndexName(), SyncHistory.SyncStatus.FAILED);
        }

        return syncHistoryRepository.save(history);
    }

    /**
     * 마지막 동기화 상태 업데이트
     */
    private void updateLastSyncState(String indexName, SyncHistory.SyncStatus status) {
        IndexState state = indexStateRepository.findById(indexName)
                .orElse(IndexState.builder().indexName(indexName).build());
        
        state.setLastSyncTime(LocalDateTime.now());
        state.setLastSyncStatus(status.name());
        indexStateRepository.save(state);
    }

    /**
     * 데이터베이스에서 배치 단위로 데이터 조회 (Paging)
     */
    private List<Map<String, Object>> fetchBatchFromDatabase(IndexDefinition definition, int limit, int offset) {
        try {
            List<FieldDefinition> fields = definition.getFields();
            if (fields.isEmpty()) {
                throw new IllegalStateException("필드 정의가 없습니다");
            }

            String selectColumns = fields.stream()
                    .map(FieldDefinition::getSourceColumn)
                    .filter(Objects::nonNull) // 매핑 전용 필드 제외
                    .collect(Collectors.joining(", "));
            
            // 정렬 기준 컬럼 (ID 컬럼 우선)
            String idColumn = definition.getIdColumn();
            if (idColumn == null || idColumn.isEmpty()) {
                idColumn = fields.get(0).getSourceColumn();
            }

            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append(String.format("SELECT %s FROM %s", selectColumns, definition.getSourceTableName()));
            
            List<Object> params = new ArrayList<>();

            // unified 인덱스인 경우 TB_CONFIG 기반 필터링 적용
            if ("unified".equals(definition.getIndexName())) {
                List<String> enabledTypes = fetchEnabledDataTypes();
                if (enabledTypes.isEmpty()) {
                    log.info("활성화된 검색 컬렉션(TB_CONFIG)이 없습니다. 동기화를 중단합니다.");
                    return Collections.emptyList();
                }
                
                String inClause = enabledTypes.stream()
                        .map(type -> "?")
                        .collect(Collectors.joining(", "));
                
                // Collation 충돌 방지를 위해 CONVERT 사용
                sqlBuilder.append(String.format(" WHERE CONVERT(DATA_TYPE USING utf8mb4) IN (%s)", inClause));
                params.addAll(enabledTypes);
            }

            // ORDER BY 및 LIMIT/OFFSET 추가
            sqlBuilder.append(String.format(" ORDER BY %s ASC LIMIT ? OFFSET ?", idColumn));
            params.add(limit);
            params.add(offset);

            String sql = sqlBuilder.toString();
            
            List<Map<String, Object>> documents = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> document = new HashMap<>();
                for (FieldDefinition field : fields) {
                    if (field.getSourceColumn() == null) continue;
                    Object value = rs.getObject(field.getSourceColumn());
                    if (value != null) {
                        document.put(field.getEffectiveFieldName(), value);
                    }
                }
                return document;
            }, params.toArray());

            // 문서 ID (_id) 설정
            final String targetIdColumn = idColumn; // lambda용 final 변수
            FieldDefinition idField = fields.stream()
                    .filter(f -> targetIdColumn.equalsIgnoreCase(f.getSourceColumn()))
                    .findFirst()
                    .orElse(null);
            
            if (idField != null) {
                String idKey = idField.getEffectiveFieldName();
                for (Map<String, Object> doc : documents) {
                    Object idVal = doc.get(idKey);
                    if (idVal != null) {
                        doc.put("id", idVal.toString());
                    }
                }
            }

            return documents;

        } catch (Exception e) {
            log.error("데이터베이스 배치 조회 실패: {} (Offset: {})", definition.getSourceTableName(), offset, e);
            throw new RuntimeException("데이터베이스 배치 조회 실패", e);
        }
    }

    /**
     * TB_CONFIG 테이블에서 활성화된(CONFIG_VALUE='Y') 검색 컬렉션 타입 조회 (File 제외)
     */
    private List<String> fetchEnabledDataTypes() {
        try {
            // KEY_PATH가 'System.SearchEngine.Collection.'으로 시작하고 CONFIG_VALUE가 'Y'인 항목 조회
            // CONFIG_KEY를 대문자로 변환하여 반환 (예: Call -> CALL)
            // 단, 'FILE'은 별도 인덱스로 관리되므로 제외
            String sql = "SELECT UPPER(CONFIG_KEY) FROM TB_CONFIG " +
                         "WHERE KEY_PATH LIKE 'System.SearchEngine.Collection.%' " +
                         "AND CONFIG_VALUE = 'Y' " +
                         "AND UPPER(CONFIG_KEY) != 'FILE'";
            
            return jdbcTemplate.queryForList(sql, String.class);
        } catch (Exception e) {
            log.error("TB_CONFIG 조회 실패", e);
            return Collections.emptyList();
        }
    }

    /**
     * 단건 문서 동기화 (실시간 인덱싱)
     */
    public void syncDocument(String indexName, String uuid) {
        // 1. 인덱스 활성화 여부 확인 (YML 설정 기반)
        if (!isIndexEnabled(indexName)) {
            log.warn("단건 동기화 건너뜀: 인덱스 '{}'가 YML 설정에 의해 비활성화되어 있습니다.", indexName);
            return;
        }

        // 2. File 인덱스 특수 처리
        if ("file".equalsIgnoreCase(indexName)) {
            fileIndexingService.indexFileByUuid(uuid);
            return;
        }

        // wiki는 야간 전량 재생성만 지원(item_no가 재생성마다 재부여되어 단건 갱신 기준이 없음)
        if ("wiki".equals(indexName)) {
            throw new UnsupportedOperationException("wiki 인덱스는 단건 동기화를 지원하지 않습니다. /api/v1/wiki-index/sync 를 사용하세요.");
        }

        IndexDefinition definition = indexRegistry.get(indexName);
        if (definition == null) {
             throw new IllegalArgumentException("알 수 없는 인덱스입니다: " + indexName);
        }

        try {
            Map<String, Object> document = fetchDocumentByUuid(definition, uuid);
            if (document == null) {
                // 문서가 DB에 없으면 OpenSearch에서도 삭제
                log.warn("데이터베이스에서 문서를 찾을 수 없습니다: uuid={}. OpenSearch에서 삭제 시도.", uuid);
                openSearchService.deleteDocument(indexName, uuid); // OpenSearch에서 삭제
                return;
            }

            // 3. unified 인덱스이고 TB_CONFIG에서 비활성화된 타입이면 인덱싱하지 않고 삭제
            if ("unified".equals(indexName)) {
                List<String> enabledTypes = fetchEnabledDataTypes(); // TB_CONFIG에서 활성화된 타입 목록 가져옴
                String documentDataType = (String) document.get("DATA_TYPE"); // 문서의 DATA_TYPE 필드
                
                if (documentDataType == null || !enabledTypes.contains(documentDataType.toUpperCase())) {
                    log.warn("단건 동기화 건너뜀: unified 인덱스의 문서 '{}' (DATA_TYPE: {})가 TB_CONFIG에서 비활성화되어 있습니다. OpenSearch에서 삭제 시도.", uuid, documentDataType);
                    openSearchService.deleteDocument(indexName, uuid); // OpenSearch에서 삭제
                    return;
                }
            }

            // 문서 ID 설정
            document.put("id", uuid);

            List<Map<String, Object>> docList = new ArrayList<>();
            docList.add(document);
            enrichDocumentsWithEmbedding(indexName, docList);

            if (docList.isEmpty()) {
                log.warn("임베딩 생성 실패 또는 벡터 필드 누락으로 인해 단건 동기화를 건너뜁니다: index={}, uuid={}", indexName, uuid);
                return;
            }

            openSearchService.indexDocument(indexName, document);
            log.info("단건 동기화 완료: index={}, uuid={}", indexName, uuid);

        } catch (Exception e) {
            log.error("단건 동기화 실패: index={}, uuid={}", indexName, uuid, e);
            throw new RuntimeException("단건 동기화 실패", e);
        }
    }

    /**
     * 단건 문서 삭제
     */
    public void deleteDocument(String indexName, String uuid) {
        if (!openSearchService.indexExists(indexName)) {
             log.warn("인덱스가 존재하지 않아 삭제를 건너뜁니다: {}", indexName);
             return;
        }
        
        boolean deleted = openSearchService.deleteDocument(indexName, uuid);
        if (deleted) {
            log.info("문서 삭제 완료: index={}, uuid={}", indexName, uuid);
        } else {
            log.warn("문서를 찾을 수 없거나 삭제에 실패했습니다: index={}, uuid={}", indexName, uuid);
        }
    }

    private Map<String, Object> fetchDocumentByUuid(IndexDefinition definition, String uuid) {
        // ... 단건 조회 로직 ...
        String idColumn = definition.getIdColumn(); // IndexDefinition에서 명확한 ID 컬럼 획득
        if (idColumn == null) {
             idColumn = "UUID"; // Fallback
        }
        
        String selectColumns = definition.getFields().stream()
                .map(FieldDefinition::getSourceColumn)
                .filter(Objects::nonNull) // 매핑 전용 필드 제외
                .collect(Collectors.joining(", "));
        
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", 
                selectColumns, definition.getSourceTableName(), idColumn);
        
        try {
            List<Map<String, Object>> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> doc = new HashMap<>();
                for (FieldDefinition f : definition.getFields()) {
                    if (f.getSourceColumn() == null) continue;
                    Object val = rs.getObject(f.getSourceColumn());
                    if (val != null) {
                        doc.put(f.getEffectiveFieldName(), val);
                    }
                }
                return doc;
            }, uuid);
            
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            throw new RuntimeException("단건 조회 실패", e);
        }
    }

    /**
     * unified 인덱스의 원본 목록 — TB_CONFIG에서 활성화된 타입만. WIKI는 store DB 접속이 있을 때만 포함.
     */
    private List<NamedSource> resolveUnifiedSources() {
        List<String> enabledTypes = fetchEnabledDataTypes();
        if (enabledTypes.isEmpty()) {
            log.info("모든 데이터 타입이 비활성화되어 있습니다(TB_CONFIG).");
            return Collections.emptyList();
        }
        List<NamedSource> sources = new ArrayList<>();
        for (String dataType : enabledTypes) {
            switch (dataType) {
                case "CALL" -> sources.add(new NamedSource(dataType,
                        (last, limit) -> fetchUnifiedBatch(dataType, "uvw_call", "CALL_UUID", "QUESTION", "ANSWER", last, limit)));
                case "MANUAL" -> sources.add(new NamedSource(dataType,
                        (last, limit) -> fetchUnifiedBatch(dataType, "uvw_manual", "MANUAL_UUID", "TITLE", "CONTENTS", last, limit)));
                case "NOTICE" -> sources.add(new NamedSource(dataType,
                        (last, limit) -> fetchUnifiedBatch(dataType, "uvw_doc_notice", "DOC_UUID", "DOC_NM", "CONTENTS", last, limit)));
                case "WIKI" -> {
                    if (wikiDataSource.isAvailable()) {
                        sources.add(new NamedSource(dataType, this::fetchUnifiedWikiBatch));
                    } else {
                        log.warn("unified WIKI 타입 건너뜀: wiki.datasource.url이 설정되지 않았습니다.");
                    }
                }
                default -> log.warn("unified 인덱스에 정의되지 않은 데이터 타입입니다. 건너뜀: {}", dataType);
            }
        }
        return sources;
    }

    /**
     * unified 인덱스용 단일 뷰 배치 조회 (Keyset Paging, UUID 단일 커서)
     * DATA_TYPE, UUID, TITLE, CONTENTS 필드만 추출
     */
    private List<Map<String, Object>> fetchUnifiedBatch(String dataType, String sourceTable, String idColumn,
                                                        String titleColumn, String contentsColumn,
                                                        Map<String, Object> lastRow, int limit) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(String.format("SELECT '%s' AS DATA_TYPE, %s AS UUID, %s AS TITLE, %s AS CONTENTS FROM %s",
                dataType, idColumn, titleColumn, contentsColumn, sourceTable));

        List<Object> params = new ArrayList<>();
        if (lastRow != null) {
            sqlBuilder.append(String.format(" WHERE %s > ?", idColumn));
            params.add(lastRow.get("UUID"));
        }
        sqlBuilder.append(String.format(" ORDER BY %s ASC LIMIT ?", idColumn));
        params.add(limit);

        try {
            return jdbcTemplate.query(sqlBuilder.toString(), (rs, rowNum) -> {
                Map<String, Object> document = new HashMap<>();
                document.put("DATA_TYPE", rs.getString("DATA_TYPE"));
                document.put("UUID", rs.getString("UUID"));
                document.put("TITLE", rs.getString("TITLE"));
                document.put("CONTENTS", rs.getString("CONTENTS"));
                document.put("id", rs.getString("UUID")); // OpenSearch _id 필드에 매핑
                return document;
            }, params.toArray());
        } catch (Exception e) {
            log.error("Unified 인덱스 '{}' 데이터 조회 실패 (lastRow: {}): {}", dataType, lastRow, e.getMessage(), e);
            throw new RuntimeException("Unified 인덱스 데이터 조회 실패", e);
        }
    }

    // wiki 항목 조회 공통부 — store DB, (doc_uuid, item_no) 복합 keyset
    private static final String WIKI_FROM =
            " FROM tb_wiki_item i" +
            " JOIN tb_wiki_doc d ON d.doc_uuid = i.doc_uuid" +
            " LEFT JOIN tb_wiki_chapter c ON c.doc_uuid = i.doc_uuid AND c.chapter_no = i.chapter_no";

    private String wikiKeysetSql(String select, Map<String, Object> lastRow, List<Object> params, int limit) {
        StringBuilder sb = new StringBuilder(select).append(WIKI_FROM);
        if (lastRow != null) {
            sb.append(" WHERE (i.doc_uuid, i.item_no) > (?, ?)");
            // wiki 인덱스는 DOC_UUID, unified WIKI 타입은 UUID에 문서 UUID를 담는다
            params.add(lastRow.containsKey("DOC_UUID") ? lastRow.get("DOC_UUID") : lastRow.get("UUID"));
            params.add(lastRow.get("ITEM_NO"));
        }
        sb.append(" ORDER BY i.doc_uuid, i.item_no LIMIT ?");
        params.add(limit);
        return sb.toString();
    }

    /**
     * wiki 인덱스 배치 조회 — 항목 1행 = 문서 1건. TITLE=topic, CONTENTS=item_text.
     */
    private List<Map<String, Object>> fetchWikiBatch(Map<String, Object> lastRow, int limit) {
        List<Object> params = new ArrayList<>();
        String sql = wikiKeysetSql(
                "SELECT i.doc_uuid, i.item_no, i.section_key, i.topic, i.item_text, i.evidence_key," +
                " i.manual_uuid, i.manual_label, i.chapter_no, c.label AS chapter_label," +
                " d.cat_id, d.doc_title, d.full_cat_nm, d.gen_no, d.gen_dt",
                lastRow, params, limit);
        try {
            return wikiDataSource.jdbc().query(sql, (rs, rowNum) -> {
                Map<String, Object> doc = new HashMap<>();
                String docUuid = rs.getString("doc_uuid");
                int itemNo = rs.getInt("item_no");
                String itemId = docUuid + ":" + itemNo;
                doc.put("id", itemId);
                doc.put("ITEM_ID", itemId);
                doc.put("DOC_UUID", docUuid);
                doc.put("CAT_ID", rs.getInt("cat_id"));
                doc.put("ITEM_NO", itemNo);
                doc.put("CHAPTER_NO", rs.getObject("chapter_no"));
                doc.put("SECTION_KEY", rs.getString("section_key"));
                doc.put("EVIDENCE_KEY", rs.getString("evidence_key"));
                doc.put("MANUAL_UUID", rs.getString("manual_uuid"));
                doc.put("MANUAL_LABEL", rs.getString("manual_label"));
                doc.put("TITLE", rs.getString("topic"));
                doc.put("CONTENTS", rs.getString("item_text"));
                doc.put("DOC_TITLE", rs.getString("doc_title"));
                doc.put("CHAPTER_LABEL", rs.getString("chapter_label"));
                doc.put("FULL_CAT_NM", rs.getString("full_cat_nm"));
                doc.put("GEN_NO", rs.getInt("gen_no"));
                doc.put("GEN_DT", rs.getObject("gen_dt"));
                doc.values().removeIf(Objects::isNull);
                return doc;
            }, params.toArray());
        } catch (Exception e) {
            log.error("wiki 인덱스 데이터 조회 실패 (lastRow: {}): {}", lastRow, e.getMessage(), e);
            throw new RuntimeException("wiki 인덱스 데이터 조회 실패", e);
        }
    }

    /**
     * unified 인덱스의 WIKI 타입 배치 조회 — UUID=doc_uuid, _id=doc_uuid:item_no.
     * 상담앱은 UUID와 ITEM_NO를 각각 받아 문서·항목을 연다.
     */
    private List<Map<String, Object>> fetchUnifiedWikiBatch(Map<String, Object> lastRow, int limit) {
        List<Object> params = new ArrayList<>();
        String sql = wikiKeysetSql(
                "SELECT i.doc_uuid, i.item_no, i.topic, i.item_text, d.cat_id, d.doc_title",
                lastRow, params, limit);
        try {
            return wikiDataSource.jdbc().query(sql, (rs, rowNum) -> {
                Map<String, Object> doc = new HashMap<>();
                String docUuid = rs.getString("doc_uuid");
                int itemNo = rs.getInt("item_no");
                doc.put("id", docUuid + ":" + itemNo);
                doc.put("DATA_TYPE", "WIKI");
                doc.put("UUID", docUuid);
                doc.put("ITEM_NO", itemNo);
                doc.put("CAT_ID", rs.getInt("cat_id"));
                doc.put("DOC_TITLE", rs.getString("doc_title"));
                doc.put("TITLE", rs.getString("topic"));
                doc.put("CONTENTS", rs.getString("item_text"));
                doc.values().removeIf(Objects::isNull);
                return doc;
            }, params.toArray());
        } catch (Exception e) {
            log.error("unified WIKI 데이터 조회 실패 (lastRow: {}): {}", lastRow, e.getMessage(), e);
            throw new RuntimeException("unified WIKI 데이터 조회 실패", e);
        }
    }

    private void enrichDocumentsWithEmbedding(String indexName, List<Map<String, Object>> documents) {
         if (vectorFieldConfig.hasVectorField(indexName) && searchIntelligenceClient.isAvailable()) {
            VectorFieldConfig.VectorField vectorField = vectorFieldConfig.getVectorField(indexName);
            String[] sourceFields = vectorField.getSourceField().split(",");
            
            List<String> textsToEmbed = new ArrayList<>();
            List<Map<String, Object>> docsToEmbed = new ArrayList<>();

            // 1. 임베딩할 텍스트 추출 및 수집
            for (Map<String, Object> doc : documents) {
                StringBuilder textBuilder = new StringBuilder();
                for (String field : sourceFields) {
                    Object value = doc.get(field.trim());
                    if (value != null) {
                        if (textBuilder.length() > 0) textBuilder.append(" ");
                        textBuilder.append(value.toString());
                    }
                }
                String text = textBuilder.toString().trim();
                
                // 텍스트가 있는 경우만 처리 대상에 포함
                if (!text.isEmpty()) {
                    textsToEmbed.add(text);
                    docsToEmbed.add(doc);
                }
            }

            // 2. 배치 임베딩 요청 및 결과 매핑
            if (!textsToEmbed.isEmpty()) {
                try {
                    List<List<Double>> embeddings = searchIntelligenceClient.embedBatch(textsToEmbed);
                    
                    if (embeddings.size() != docsToEmbed.size()) {
                        log.warn("요청한 텍스트 수({})와 반환된 임베딩 수({})가 일치하지 않습니다.", 
                                textsToEmbed.size(), embeddings.size());
                    }

                    for (int i = 0; i < embeddings.size(); i++) {
                        if (i < docsToEmbed.size()) {
                            docsToEmbed.get(i).put(vectorField.getTargetField(), embeddings.get(i));
                        }
                    }
                } catch (Exception e) {
                    log.error("배치 임베딩 생성 중 오류 발생: index={}", indexName, e);
                    // 실패 시 개별 문서는 임베딩 없이 진행됨 (또는 필요 시 재시도 로직 추가)
                }
            }
            
            // 임베딩 필드가 누락된 문서 제거 (OpenSearch 인덱싱 오류 방지)
            if (vectorFieldConfig.hasVectorField(indexName)) {
                // 이미 상단에 vectorField가 선언되어 있으므로 재사용
                documents.removeIf(doc -> !doc.containsKey(vectorField.getTargetField()) || doc.get(vectorField.getTargetField()) == null);
            }
        }
    }

    // --- 스케줄러용 메서드 ---

    /**
     * 모든 활성화된 인덱스 동기화
     */
    public void syncAllEnabledIndexes() {
        indexRegistry.getDefinitions().keySet().forEach(indexName -> {
            if (isIndexEnabled(indexName)) {
                try {
                    syncIndex(indexName);
                } catch (Exception e) {
                    log.error("동기화 실패: {}", indexName, e);
                }
            }
        });
    }

    /**
     * 모든 활성화된 인덱스를 삭제 후 재생성(Re-index)
     * 스케줄러 등에서 주기적으로 클린 인덱싱을 위해 사용
     */
    public void reindexAllEnabledIndexes() {
        indexRegistry.getDefinitions().keySet().forEach(indexName -> {
            if (isIndexEnabled(indexName)) {
                // File 인덱스의 경우 별도 설정 확인
                if ("file".equalsIgnoreCase(indexName) && !isFileConfigEnabled()) {
                    return;
                }
                // 전용 스케줄(cron)이 있는 인덱스는 전역 동기화에서 제외
                if (hasOwnCron(indexName)) {
                    log.info("전역 재설정에서 제외(전용 스케줄): {}", indexName);
                    return;
                }

                try {
                    log.info("인덱스 재설정(삭제 후 생성) 시작: {}", indexName);
                    // 1. 인덱스 삭제
                    if (openSearchService.indexExists(indexName)) {
                        openSearchService.deleteIndex(indexName);
                        try {
                            Thread.sleep(1000); // 인덱스 삭제 후 OpenSearch 상태 반영 대기
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    
                    // 2. 인덱스 동기화 (생성 및 데이터 주입)
                    syncIndex(indexName);
                    
                } catch (Exception e) {
                    log.error("인덱스 재설정 실패: {}", indexName, e);
                }
            }
        });
    }

    /**
     * 주기적 동기화 체크
     */
    @Transactional
    public void checkAndSyncIntervalIndexes() {
        Map<String, SearchIndexProperties.IndexSettings> settingsMap = indexProperties.getIndexes();
        if (settingsMap == null) return;

        LocalDateTime now = LocalDateTime.now();

        settingsMap.forEach((indexName, settings) -> {
            // enabled=true AND autoSync=true 인지 확인
            if (settings.isEnabled() && settings.isAutoSync()) {
                // 마지막 동기화 시간 확인 (DB)
                IndexState state = indexStateRepository.findById(indexName).orElse(null);
                
                boolean shouldSync = false;
                if (state == null || state.getLastSyncTime() == null) {
                    shouldSync = true;
                } else {
                    int interval = settings.getSyncIntervalMinutes();
                    if (interval > 0) {
                        LocalDateTime nextSyncTime = state.getLastSyncTime().plusMinutes(interval);
                        if (nextSyncTime.isBefore(now)) {
                            shouldSync = true;
                        }
                    }
                }

                if (shouldSync) {
                    log.info("주기적 동기화 실행: {}", indexName);
                    try {
                        syncIndex(indexName);
                    } catch (Exception e) {
                        log.error("주기적 동기화 실패: {}", indexName, e);
                    }
                }
            }
        });
    }

    private boolean hasOwnCron(String indexName) {
        if (indexProperties.getIndexes() == null) return false;
        SearchIndexProperties.IndexSettings settings = indexProperties.getIndexes().get(indexName);
        return settings != null && settings.getCron() != null && !settings.getCron().isBlank();
    }

    private boolean isIndexEnabled(String indexName) {
        if (indexProperties.getIndexes() == null) return false;
        SearchIndexProperties.IndexSettings settings = indexProperties.getIndexes().get(indexName);
        return settings != null && settings.isEnabled();
    }

    /**
     * 파일 인덱스 실행 여부를 DB 설정(tb_config)에서 조회
     */
    private boolean isFileConfigEnabled() {
        try {
            String sql = "SELECT config_value FROM tb_config WHERE key_path = 'System.SearchEngine.Collection.File'";
            List<String> results = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("config_value"));

            if (results.isEmpty()) {
                log.warn("파일 인덱스 설정(System.SearchEngine.Collection.File)이 DB에 없습니다. 기본값(N) 처리합니다.");
                return false;
            }

            return "Y".equalsIgnoreCase(results.get(0));
        } catch (Exception e) {
            log.error("파일 인덱스 설정 조회 중 오류 발생", e);
            return false;
        }
    }
}