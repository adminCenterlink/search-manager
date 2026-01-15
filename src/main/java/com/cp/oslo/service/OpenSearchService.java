package com.cp.oslo.service;

import com.cp.oslo.config.VectorFieldConfig;
import com.cp.oslo.model.FieldDefinition;
import com.cp.oslo.model.IndexDefinition;
import com.cp.oslo.util.AnalyzerConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.*;
import org.opensearch.client.opensearch._types.analysis.NoriDecompoundMode;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.json.JsonData;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.List;
import com.cp.oslo.dto.SearchResultDto;
import org.opensearch.client.opensearch.core.search.BuiltinHighlighterType;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch._types.query_dsl.Operator;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch._types.FieldValue;

/**
 * OpenSearch 인덱스 및 문서 관리 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenSearchService {

    private static final int HIGHLIGHT_FRAGMENT_SIZE = 200;
    private static final int HIGHLIGHT_NUM_OF_FRAGMENTS = 1;

    private final OpenSearchClient openSearchClient;
    private final AnalyzerConfigLoader analyzerConfigLoader;
    private final VectorFieldConfig vectorFieldConfig;
    private final ObjectMapper objectMapper;

    /**
     * 특정 필드 값이 허용된 목록에 포함되지 않는 문서들을 삭제합니다.
     * (예: DATA_TYPE이 [MANUAL, DOC]에 속하지 않는 문서 삭제)
     */
    public void deleteDocumentsNotInTypes(String indexName, String fieldName, List<String> allowedTypes) {
        try {
            if (!indexExists(indexName)) {
                return;
            }

            if (allowedTypes == null || allowedTypes.isEmpty()) {
                log.warn("허용된 타입 목록이 비어 있습니다. 삭제 작업을 건너뜁니다.");
                return;
            }

            // 쿼리: Must Not Terms (fieldName IN allowedTypes)
            // 즉, allowedTypes에 포함되지 않는 문서들을 찾아서 삭제
            DeleteByQueryRequest request = new DeleteByQueryRequest.Builder()
                    .index(indexName)
                    .query(q -> q
                            .bool(b -> b
                                    .mustNot(mn -> mn
                                            .terms(t -> t
                                                    .field(fieldName)
                                                    .terms(tt -> tt
                                                            .value(allowedTypes.stream()
                                                                    .map(FieldValue::of)
                                                                    .collect(Collectors.toList()))
                                                    )
                                            )
                                    )
                            )
                    )
                    .build();

            openSearchClient.deleteByQuery(request);
            log.info("인덱스 '{}'에서 허용되지 않은 타입의 문서를 삭제했습니다. (허용된 타입: {})", indexName, allowedTypes);

        } catch (Exception e) {
            log.error("문서 삭제(deleteByQuery) 실패", e);
            throw new RuntimeException("문서 삭제 실패", e);
        }
    }

    /**
     * 인덱스 생성
     */
    public boolean createIndex(IndexDefinition definition) {
        try {
            // 인덱스 존재 여부 확인
            if (indexExists(definition.getIndexName())) {
                log.warn("인덱스가 이미 존재합니다: {}", definition.getIndexName());
                return false;
            }

            // 매핑 생성 (YAML 벡터 필드 포함)
            Map<String, Property> properties = createMappingProperties(
                    definition.getFields(), 
                    definition.getIndexName()
            );

            // 인덱스 설정 생성 (analyzer 포함)
            // 680만 건(unified) 기준 샤드 5개 권장 (병렬 처리 최적화)
            int shards = "unified".equals(definition.getIndexName()) ? 5 : 1;
            IndexSettings indexSettings = createIndexSettings(
                    shards, 
                    1, // 기본값 하드코딩
                    definition
            );

            // 인덱스 생성 요청
            CreateIndexRequest createIndexRequest = CreateIndexRequest.of(c -> c
                    .index(definition.getIndexName())
                    .settings(indexSettings)
                    .mappings(m -> m.properties(properties))
            );

            openSearchClient.indices().create(createIndexRequest);
            log.info("인덱스 생성 완료: {}", definition.getIndexName());
            return true;

        } catch (Exception e) {
            log.error("인덱스 생성 실패: {}", definition.getIndexName(), e);
            try {
                // 에러 발생 시 요청 JSON 본문을 다시 로깅
                CreateIndexRequest createIndexRequest = CreateIndexRequest.of(c -> c
                        .index(definition.getIndexName())
                        .settings(createIndexSettings(
                                "unified".equals(definition.getIndexName()) ? 5 : 1, // 샤드 수
                                1, // 복제본 수
                                definition
                        ))
                        .mappings(m -> m.properties(createMappingProperties(
                                definition.getFields(),
                                definition.getIndexName()
                        )))
                );
                log.error("실패한 CreateIndexRequest JSON: {}", objectMapper.writeValueAsString(createIndexRequest));
            } catch (Exception jsonE) {
                log.error("CreateIndexRequest JSON 직렬화 실패: {}", jsonE.getMessage());
            }
            throw new RuntimeException("인덱스 생성 실패", e);
        }
    }

    /**
     * 인덱스 삭제
     */
    public boolean deleteIndex(String indexName) {
        try {
            if (!indexExists(indexName)) {
                log.warn("인덱스가 존재하지 않습니다: {}", indexName);
                return false;
            }

            DeleteIndexRequest request = DeleteIndexRequest.of(d -> d.index(indexName));
            openSearchClient.indices().delete(request);
            log.info("인덱스 삭제 완료: {}", indexName);
            return true;

        } catch (Exception e) {
            log.error("인덱스 삭제 실패: {}", indexName, e);
            throw new RuntimeException("인덱스 삭제 실패", e);
        }
    }

    /**
     * 인덱스 존재 여부 확인
     */
    public boolean indexExists(String indexName) {
        try {
            ExistsRequest request = ExistsRequest.of(e -> e.index(indexName));
            return openSearchClient.indices().exists(request).value();
        } catch (Exception e) {
            log.error("인덱스 존재 여부 확인 실패: {}", indexName, e);
            return false;
        }
    }

    /**
     * OpenSearch 서비스 연결 확인
     */
    public boolean isAvailable() {
        try {
            return openSearchClient.ping().value();
        } catch (Exception e) {
            log.warn("OpenSearch 서비스 연결 실패: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 필드 목록에 KNN_VECTOR 타입이 포함되어 있는지 재귀적으로 확인
     */
    private boolean hasKnnVector(List<FieldDefinition> fields) {
        if (fields == null) return false;
        for (FieldDefinition field : fields) {
            if (field.getType() == FieldDefinition.FieldType.KNN_VECTOR) {
                return true;
            }
            if (field.getType() == FieldDefinition.FieldType.NESTED) {
                if (hasKnnVector(field.getSubFields())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Bulk 문서 인덱싱
     */
    public BulkIndexResult bulkIndex(String indexName, List<Map<String, Object>> documents) {
        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

            for (Map<String, Object> doc : documents) {
                String docId = doc.get("id") != null ? doc.get("id").toString() : null;
                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(indexName)
                                .id(docId)
                                .document(doc)
                        )
                );
            }

            BulkResponse result = openSearchClient.bulk(bulkBuilder.build());

            long successCount = 0;
            long failCount = 0;
            String firstErrorReason = null;

            if (result.errors()) {
                for (BulkResponseItem item : result.items()) {
                    if (item.error() != null) {
                        failCount++;
                        // 첫 번째 에러만 저장
                        if (firstErrorReason == null) {
                            firstErrorReason = item.error().reason();
                        }
                    } else {
                        successCount++;
                    }
                }
                // 실패가 있을 때만 첫 번째 에러만 로그
                if (failCount > 0) {
                    log.warn("Bulk 인덱싱 중 {}건 실패 (첫 번째 에러: {})", failCount, firstErrorReason);
                }
            } else {
                successCount = documents.size();
            }

            return new BulkIndexResult(successCount, failCount, result.errors());

        } catch (Exception e) {
            log.error("Bulk 인덱싱 실패", e);
            throw new RuntimeException("Bulk 인덱싱 실패", e);
        }
    }

    /**
     * 단건 문서 인덱싱
     */
    public boolean indexDocument(String indexName, Map<String, Object> document) {
        try {
            String docId = null;
            if (document.get("id") != null) {
                docId = document.get("id").toString();
            } else if (document.get("UUID") != null) {
                docId = document.get("UUID").toString();
            } else if (document.get("uuid") != null) {
                docId = document.get("uuid").toString();
            }

            // Jackson ObjectMapper로 JSON 문자열로 직렬화
            ObjectMapper objectMapper = new ObjectMapper();
            // Java 8 날짜/시간 타입 지원 추가
            objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            String jsonString = objectMapper.writeValueAsString(document);

            // RestClient를 직접 사용하여 JSON 문자열 전송
            org.opensearch.client.transport.rest_client.RestClientTransport transport = 
                (org.opensearch.client.transport.rest_client.RestClientTransport) openSearchClient._transport();
            org.opensearch.client.transport.OpenSearchTransport rawTransport = transport;

            String endpoint = "/" + indexName + "/_doc" + (docId != null ? "/" + docId : "");
            org.apache.http.HttpEntity entity = new org.apache.http.nio.entity.NStringEntity(
                jsonString, org.apache.http.entity.ContentType.APPLICATION_JSON);

            org.opensearch.client.Request request = new org.opensearch.client.Request("POST", endpoint);
            request.setEntity(entity);

            org.opensearch.client.Response response = transport.restClient().performRequest(request);

            return response.getStatusLine().getStatusCode() == 200 ||
                   response.getStatusLine().getStatusCode() == 201;
        } catch (Exception e) {
            log.error("문서 인덱싱 실패: index={}, doc={}", indexName, document, e);
            throw new RuntimeException("문서 인덱싱 실패", e);
        }
    }
    
    // 검색 메서드들은 변경 없음 (String indexName 사용)
    public SearchResultDto search(String indexName, String query, String operator, Integer size) {
        // ... (기존 코드 유지)
        try {
            // operator 기본값 및 검증
            String defaultOperator = (operator != null && "AND".equalsIgnoreCase(operator)) ? "AND" : "OR";

            // size 기본값 및 제한 (최소 1, 최대 100, 기본 10)
            int resultSize = 10;
            if (size != null) {
                resultSize = Math.max(1, Math.min(size, 100));
            }
            
            // file 인덱스 특수 처리 (Nested 구조 전문 검색)
            if ("file".equalsIgnoreCase(indexName)) {
                return searchNestedText(indexName, "paragraphs", "content", query, resultSize);
            }
            
            // Determine target fields based on indexName
            List<String> targetFields;
            if ("call".equals(indexName)) {
                targetFields = List.of("QUESTION", "ANSWER");
            } else if ("manual-qna".equals(indexName)) {
                targetFields = List.of("TITLE", "CONTENTS");
            } else if ("manual".equals(indexName)) { // manual 인덱스에 CAT_NM 필드 추가
                targetFields = List.of("TITLE", "CONTENTS", "CAT_NM");
            } else if ("notice".equals(indexName)) {
                targetFields = List.of("DOC_NM", "CONTENTS");
            } else if ("unified".equals(indexName)) {
                targetFields = List.of("TITLE", "CONTENTS");
            } else { 
                targetFields = List.of("TITLE", "CONTENTS");
            }    

            SearchRequest.Builder searchRequestBuilder = new SearchRequest.Builder()
                    .index(indexName)
                    .size(resultSize)
                    .query(q -> q
                            .bool(b -> {
                                b.must(must -> must
                                    .multiMatch(m -> m
                                            .fields(targetFields)
                                            .query(query)
                                            .operator("AND".equalsIgnoreCase(defaultOperator)
                                                    ? Operator.And
                                                    : Operator.Or
                                            )
                                            .type(org.opensearch.client.opensearch._types.query_dsl.TextQueryType.CrossFields)
                                            .minimumShouldMatch("AND".equalsIgnoreCase(defaultOperator) ? null : 
                                                (!query.trim().contains(" ") ? "2<100%" : null)
                                            )
                                    )
                                );
                                
                                // unified 인덱스인 경우 MANUAL 데이터 타입에 가중치 부여
                                if ("unified".equals(indexName)) {
                                    b.should(s -> s
                                        .term(t -> t
                                            .field("DATA_TYPE")
                                            .value(FieldValue.of("MANUAL"))
                                            .boost(3.0f)
                                        )
                                    );
                                }
                                return b;
                            })
                    );

            // manual 인덱스에만 하이라이팅 적용
            if ("manual".equals(indexName)) {
                searchRequestBuilder.highlight(h -> h
                        .fields("TITLE", f -> f
                                .type(t -> t.builtin(BuiltinHighlighterType.Unified))
                                .preTags("<b>")
                                .postTags("</b>")
                                .fragmentSize(100)
                                .numberOfFragments(1)
                        )
                        .fields("CONTENTS", f -> f
                                .type(t -> t.builtin(BuiltinHighlighterType.Unified))
                                .preTags("<b>")
                                .postTags("</b>")
                                .fragmentSize(100)
                                .numberOfFragments(1)
                        )
                        .fields("CAT_NM", f -> f // CAT_NM 필드도 하이라이팅에 포함
                                .type(t -> t.builtin(BuiltinHighlighterType.Unified))
                                .preTags("<b>")
                                .postTags("</b>")
                                .fragmentSize(100)
                                .numberOfFragments(1)
                        )
                );
            }
            
            // search 인덱스 하이라이팅 적용
            if ("unified".equals(indexName)) {
                searchRequestBuilder.highlight(h -> h
                        .fields("TITLE", f -> f
                                .type(t -> t.builtin(BuiltinHighlighterType.Unified))
                                .preTags("<b>")
                                .postTags("</b>")
                                .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)
                                .numberOfFragments(HIGHLIGHT_NUM_OF_FRAGMENTS)
                        )
                        .fields("CONTENTS", f -> f
                                .type(t -> t.builtin(BuiltinHighlighterType.Unified))
                                .preTags("<b>")
                                .postTags("</b>")
                                .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)
                                .numberOfFragments(HIGHLIGHT_NUM_OF_FRAGMENTS)
                        )
                );
            }

            SearchResponse<Map> response = openSearchClient.search(searchRequestBuilder.build(), Map.class);
            
            List<Map<String, Object>> documents = response.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> source = new HashMap<>(hit.source());
                        if (source != null) {
                            source.put("_id", hit.id());
                            source.put("_score", hit.score());
                        }
                        // 하이라이트 정보를 문서 자체에 병합하지 않고 별도로 관리 (SearchResultDto에)
                        return source;
                    })
                    .collect(Collectors.toList());
            
            // 하이라이트 결과 파싱
            Map<String, Map<String, List<String>>> highlights = new HashMap<>();
            response.hits().hits().forEach(hit -> {
                if (hit.highlight() != null && !hit.highlight().isEmpty()) {
                    highlights.put(hit.id(), hit.highlight());
                }
            });

            return SearchResultDto.builder()
                    .totalHits(response.hits().total().value())
                    .documents(documents)
                    .highlights(highlights)
                    .build();

        } catch (Exception e) {
            log.error("검색 실패", e);
            throw new RuntimeException("검색 실패", e);
        }
    }

    public SearchResponse<Map> vectorSearch(String indexName, String vectorFieldName, List<Double> queryVector, Integer k) {
        // ... (기존 코드 유지)
        try {
            int resultSize = 10;
            if (k != null) {
                resultSize = Math.max(1, Math.min(k, 100));
            }
            final int finalK = resultSize;
            float[] vectorArray = new float[queryVector.size()];
            for (int i = 0; i < queryVector.size(); i++) {
                vectorArray[i] = queryVector.get(i).floatValue();
            }
            SearchResponse<Map> response = openSearchClient.search(s -> s
                            .index(indexName)
                            .size(finalK)
                            .query(q -> q
                                    .knn(knn -> knn
                                            .field(vectorFieldName)
                                            .vector(vectorArray)
                                            .k(finalK)
                                    )
                            ),
                    Map.class
            );
            return response;
        } catch (Exception e) {
            throw new RuntimeException("벡터 검색 실패", e);
        }
    }

    public SearchResultDto hybridSearch(String indexName, List<String> textFields, String vectorFieldName, 
                                           String queryText, List<Double> queryVector, 
                                           Double textWeight, Integer size) {
        
        // file 인덱스 특수 처리 (Nested 구조)
        if ("file".equalsIgnoreCase(indexName)) {
            return searchNestedHybrid(indexName, "paragraphs", "content", "embedding", queryText, queryVector, textWeight, size);
        }

        try {
            double textScore = (textWeight != null) ? Math.max(0.0, Math.min(textWeight, 1.0)) : 0.5;
            double vectorScore = 1.0 - textScore;
            int resultSize = 10;
            if (size != null) {
                resultSize = Math.max(1, Math.min(size, 100));
            }
            final int finalSize = resultSize;
            final double finalTextScore = textScore;
            final double finalVectorScore = vectorScore;
            float[] vectorArray = new float[queryVector.size()];
            for (int i = 0; i < queryVector.size(); i++) {
                vectorArray[i] = queryVector.get(i).floatValue();
            }

            SearchRequest.Builder searchRequestBuilder = new SearchRequest.Builder()
                    .index(indexName)
                    .size(finalSize)
                    .query(q -> q
                            .bool(b -> b
                                    .should(sh -> sh
                                            .multiMatch(mm -> mm
                                                    .fields(textFields)
                                                    .query(queryText)
                                                    .boost((float) finalTextScore * 10.0f) // 텍스트 매칭 중요도 상향
                                            )
                                    )
                                    .should(sh -> sh
                                            .bool(b2 -> b2
                                                    .should(s -> s
                                                            .matchPhrase(mp -> mp
                                                                    .field("TITLE")
                                                                    .query(queryText)
                                                                    .slop(2) // 단어 사이 간격 허용
                                                                    .boost((float) finalTextScore * 20.0f) // 정확 매칭 중요도 대폭 상향
                                                            )
                                                    )
                                                    .should(s -> s
                                                            .matchPhrase(mp -> mp
                                                                    .field("CONTENTS")
                                                                    .query(queryText)
                                                                    .slop(2)
                                                                    .boost((float) finalTextScore * 20.0f) // 정확 매칭 중요도 대폭 상향
                                                            )
                                                    )
                                            )
                                    )
                                    .should(sh -> sh
                                            .knn(knn -> knn
                                                    .field(vectorFieldName)
                                                    .vector(vectorArray)
                                                    .k(finalSize)
                                                    .boost((float) finalVectorScore)
                                            )
                                    )
                                    // MANUAL 데이터 타입에 가중치 부여
                                    .should(sh -> sh
                                            .term(t -> t
                                                    .field("DATA_TYPE")
                                                    .value(FieldValue.of("MANUAL"))
                                                    .boost((float) finalTextScore * 2.0f) // 상대적 비중 조정
                                            )
                                    )
                            )
                    )
                    // 하이라이팅 추가 (리랭킹 입력용)
                    .highlight(h -> h
                            .fields("TITLE", f -> f
                                    // 기본 하이라이터 사용 (type 제거)
                                    .preTags("<b>")
                                    .postTags("</b>")
                                    .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE) // 리랭킹에 충분한 컨텍스트 제공 위해 크기 증가
                                    .numberOfFragments(HIGHLIGHT_NUM_OF_FRAGMENTS)
                            )
                            .fields("CONTENTS", f -> f
                                    // 기본 하이라이터 사용 (type 제거)
                                    .preTags("<b>")
                                    .postTags("</b>")
                                    .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)
                                    .numberOfFragments(HIGHLIGHT_NUM_OF_FRAGMENTS) // 여러 문단 매칭될 수 있으므로
                            )
                    );

            SearchResponse<Map> response = openSearchClient.search(searchRequestBuilder.build(), Map.class);

            // 하이라이트 결과 파싱
            Map<String, Map<String, List<String>>> highlights = new HashMap<>();
            response.hits().hits().forEach(hit -> {
                if (hit.highlight() != null && !hit.highlight().isEmpty()) {
                    highlights.put(hit.id(), hit.highlight());
                }
            });

            List<Map<String, Object>> documents = response.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> source = new HashMap<>(hit.source());
                        if (source != null) {
                            source.put("_id", hit.id());
                            source.put("_score", hit.score());
                        }
                        return source;
                    })
                    .collect(Collectors.toList());
            
            return SearchResultDto.builder()
                    .totalHits(response.hits().total().value())
                    .documents(documents)
                    .highlights(highlights) // 하이라이트 정보 포함
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("하이브리드 검색 실패", e);
        }
    }

    /**
     * Nested 필드 대상 하이브리드 검색
     */
    public SearchResultDto searchNestedHybrid(String indexName, String nestedPath, 
                                              String textField, String vectorField,
                                              String queryText, List<Double> queryVector,
                                              Double textWeight, Integer size) {
        try {
            double textScore = (textWeight != null) ? Math.max(0.0, Math.min(textWeight, 1.0)) : 0.5;
            double vectorScore = 1.0 - textScore;
            
            float[] vectorArray = new float[queryVector.size()];
            for (int i = 0; i < queryVector.size(); i++) {
                vectorArray[i] = queryVector.get(i).floatValue();
            }

            SearchRequest request = new SearchRequest.Builder()
                    .index(indexName)
                    .size(size)
                    .query(q -> q
                            .bool(b -> b
                                    .should(s -> s
                                            .multiMatch(m -> m // FILE_NM을 텍스트 검색에 포함
                                                    .fields("FILE_NM")
                                                    .query(queryText)
                                                    .boost((float) textScore * 10.0f)
                                            )
                                    )
                                    .should(s -> s
                                            .nested(n -> n
                                                    .path(nestedPath)
                                                    .query(nq -> nq
                                                            .match(m -> m
                                                                    .field(nestedPath + "." + textField)
                                                                    .query(FieldValue.of(queryText))
                                                                    .boost((float) textScore * 10.0f)
                                                            )
                                                    )
                                                    .scoreMode(org.opensearch.client.opensearch._types.query_dsl.ChildScoreMode.Max)
                                                    .innerHits(ih -> ih
                                                            .name("nested_highlights")
                                                            .highlight(ihh -> ihh
                                                                    .fields(nestedPath + "." + textField, f -> f
                                                                            .preTags("<b>")
                                                                            .postTags("</b>")
                                                                            .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)
                                                                            .numberOfFragments(HIGHLIGHT_NUM_OF_FRAGMENTS)
                                                                            .requireFieldMatch(true)
                                                                            .highlightQuery(hq -> hq
                                                                                .matchPhrase(mp -> mp
                                                                                    .field(nestedPath + "." + textField)
                                                                                    .query(queryText)
                                                                                    .analyzer("nori_custom")
                                                                                )
                                                                            )
                                                                    )
                                                            )
                                                            .size(HIGHLIGHT_NUM_OF_FRAGMENTS) // 매칭된 문단 최대 1개 가져오기
                                                    )
                                            )
                                    )
                                    .should(s -> s
                                            .nested(n -> n
                                                    .path(nestedPath)
                                                    .query(nq -> nq
                                                            .knn(k -> k
                                                                    .field(nestedPath + "." + vectorField)
                                                                    .vector(vectorArray)
                                                                    .k(size)
                                                                    .boost((float) vectorScore)
                                                            )
                                                    )
                                                    .scoreMode(org.opensearch.client.opensearch._types.query_dsl.ChildScoreMode.Max)
                                                    .innerHits(ih -> ih
                                                            .name("nested_vectors")
                                                            .size(1) // 벡터 매칭 문단 1개
                                                    )
                                            )
                                    )
                            )
                    )
                    .highlight(h -> h
                            .fields("FILE_NM", f -> f
                                    .preTags("<b>")
                                    .postTags("</b>")
                                    .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)
                                    .numberOfFragments(HIGHLIGHT_NUM_OF_FRAGMENTS)
                                    .requireFieldMatch(true)
                                    .highlightQuery(hq -> hq
                                        .matchPhrase(mp -> mp
                                            .field("FILE_NM")
                                            .query(queryText)
                                            .analyzer("nori_custom")
                                        )
                                    )
                            )
                    )
                    .build();

            SearchResponse<Map> response = openSearchClient.search(request, Map.class);

            // 하이라이트 결과 파싱
            Map<String, Map<String, List<String>>> highlights = new HashMap<>();
            response.hits().hits().forEach(hit -> {
                Map<String, List<String>> docHighlights = new HashMap<>();
                
                // 1. 상위 문서 하이라이트 (FILE_NM)
                if (hit.highlight() != null) {
                    docHighlights.putAll(hit.highlight());
                }
                
                // 2. Nested Inner Hits 하이라이트 (content)
                if (hit.innerHits() != null && hit.innerHits().containsKey("nested_highlights")) {
                    var innerHitsResult = hit.innerHits().get("nested_highlights");
                    java.util.List<String> collectedContents = new java.util.ArrayList<>();
                    
                    for (var innerHit : innerHitsResult.hits().hits()) {
                        if (innerHit.highlight() != null && innerHit.highlight().containsKey(nestedPath + "." + textField)) {
                            collectedContents.addAll(innerHit.highlight().get(nestedPath + "." + textField));
                        } else if (innerHit.source() != null) {
                            // 하이라이트가 없으면 소스에서 직접 가져옴
                            Map<String, Object> innerSource;
                            Object sourceObj = innerHit.source();
                            if (sourceObj instanceof org.opensearch.client.json.JsonData) {
                                innerSource = ((org.opensearch.client.json.JsonData) sourceObj).to(Map.class);
                            } else {
                                innerSource = (Map<String, Object>) sourceObj;
                            }
                            
                            if (innerSource.containsKey(textField)) {
                                String text = innerSource.get(textField).toString();
                                if (text.length() > HIGHLIGHT_FRAGMENT_SIZE) {
                                    text = text.substring(0, HIGHLIGHT_FRAGMENT_SIZE);
                                }
                                collectedContents.add(text);
                            }
                        }
                    }
                    if (!collectedContents.isEmpty()) {
                        docHighlights.put(nestedPath + "." + textField, collectedContents);
                    }
                }
                
                if (!docHighlights.isEmpty()) {
                    highlights.put(hit.id(), docHighlights);
                }
            });
            
            List<Map<String, Object>> documents = response.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> source = new HashMap<>();
                        if (hit.source() != null) {
                            source.putAll(hit.source());
                        }
                        source.put("_id", hit.id());
                        source.put("_score", hit.score());
                        return source;
                    })
                    .collect(Collectors.toList());

            return SearchResultDto.builder()
                    .totalHits(response.hits().total().value())
                    .documents(documents)
                    .highlights(highlights) // 하이라이트 정보 포함
                    .build();

        } catch (Exception e) {
            log.error("Nested 하이브리드 검색 실패", e);
            throw new RuntimeException("Nested 하이브리드 검색 실패", e);
        }
    }

    /**
     * Nested 필드 대상 전문 검색 (텍스트만)
     */
    public SearchResultDto searchNestedText(String indexName, String nestedPath, 
                                            String textField, String queryText, int size) {
        try {
            SearchRequest request = new SearchRequest.Builder()
                    .index(indexName)
                    .size(size)
                    .query(q -> q
                            .bool(b -> b
                                    .should(s -> s
                                            .match(m -> m
                                                    .field("FILE_NM")
                                                    .query(FieldValue.of(queryText))
                                            )
                                    )
                                    .should(s -> s
                                            .nested(n -> n
                                                    .path(nestedPath)
                                                    .query(nq -> nq
                                                            .match(m -> m
                                                                    .field(nestedPath + "." + textField)
                                                                    .query(FieldValue.of(queryText))
                                                            )
                                                    )
                                                    .scoreMode(org.opensearch.client.opensearch._types.query_dsl.ChildScoreMode.Max)
                                                    .innerHits(ih -> ih
                                                            .name("nested_highlights")
                                                            .highlight(ihh -> ihh
                                                                    .fields(nestedPath + "." + textField, f -> f
                                                                            .preTags("<b>")
                                                                            .postTags("</b>")
                                                                            .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)
                                                                            .numberOfFragments(HIGHLIGHT_NUM_OF_FRAGMENTS)
                                                                            .requireFieldMatch(true)
                                                                            .highlightQuery(hq -> hq
                                                                                .matchPhrase(mp -> mp
                                                                                    .field(nestedPath + "." + textField)
                                                                                    .query(queryText)
                                                                                    .analyzer("nori_custom")
                                                                                )
                                                                            )
                                                                    )
                                                            )
                                                    )
                                            )
                                    )
                                    .minimumShouldMatch("1")
                            )
                    )
                    .highlight(h -> h
                            .fields("FILE_NM", f -> f
                                    .preTags("<b>")
                                    .postTags("</b>")
                                    .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)
                                    .numberOfFragments(HIGHLIGHT_NUM_OF_FRAGMENTS)
                                    .requireFieldMatch(true)
                                    .highlightQuery(hq -> hq
                                        .matchPhrase(mp -> mp
                                            .field("FILE_NM")
                                            .query(queryText)
                                            .analyzer("nori_custom")
                                        )
                                    )
                            )
                    )
                    .build();

            SearchResponse<Map> response = openSearchClient.search(request, Map.class);
            
            // 하이라이트 결과 파싱 (Inner Hits 포함)
            Map<String, Map<String, List<String>>> highlights = new HashMap<>();
            response.hits().hits().forEach(hit -> {
                Map<String, List<String>> docHighlights = new HashMap<>();
                
                // 1. 상위 문서 하이라이트 (FILE_NM)
                if (hit.highlight() != null) {
                    docHighlights.putAll(hit.highlight());
                }
                
                // 2. Nested Inner Hits 하이라이트 (content)
                if (hit.innerHits() != null && hit.innerHits().containsKey("nested_highlights")) {
                    var innerHitsResult = hit.innerHits().get("nested_highlights");
                    for (var innerHit : innerHitsResult.hits().hits()) {
                        if (innerHit.highlight() != null) {
                            docHighlights.putAll(innerHit.highlight());
                            // 첫 번째 매칭된 문단만 사용하려면 break;
                            // 여기서는 여러 문단 중 하나라도 있으면 추가됨
                        }
                    }
                }
                
                if (!docHighlights.isEmpty()) {
                    highlights.put(hit.id(), docHighlights);
                }
            });
            
            List<Map<String, Object>> documents = response.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> source = new HashMap<>();
                        if (hit.source() != null) {
                            source.putAll(hit.source());
                        }
                        source.put("_id", hit.id());
                        source.put("_score", hit.score());
                        return source;
                    })
                    .collect(Collectors.toList());

            return SearchResultDto.builder()
                    .totalHits(response.hits().total().value())
                    .documents(documents)
                    .highlights(highlights) // 하이라이트 정보 포함
                    .build();

        } catch (Exception e) {
            log.error("Nested 전문 검색 실패", e);
            throw new RuntimeException("Nested 전문 검색 실패", e);
        }
    }

    /**
     * 단건 문서 삭제
     */
    public boolean deleteDocument(String indexName, String docId) {
        try {
            org.opensearch.client.opensearch.core.DeleteRequest request = 
                org.opensearch.client.opensearch.core.DeleteRequest.of(d -> d
                    .index(indexName)
                    .id(docId)
            );
            org.opensearch.client.opensearch.core.DeleteResponse response = openSearchClient.delete(request);
            return response.result() == org.opensearch.client.opensearch._types.Result.Deleted;
        } catch (Exception e) {
            log.error("문서 삭제 실패: index={}, id={}", indexName, docId, e);
            throw new RuntimeException("문서 삭제 실패", e);
        }
    }

    /**
     * 인덱스 설정 생성 (custom analyzer 포함)
     */
    private IndexSettings createIndexSettings(Integer numberOfShards, Integer numberOfReplicas, IndexDefinition definition) {
        List<String> synonyms = analyzerConfigLoader.loadSynonyms();
        List<String> stopwords = analyzerConfigLoader.loadStopwords();
        List<String> dictionary = analyzerConfigLoader.loadUserDictionary();
        
        // 공통 필터 (소문자, 조사 제거 등)
        List<String> commonFilters = new java.util.ArrayList<>();
        commonFilters.add("lowercase");
        commonFilters.add("nori_part_of_speech"); 
        
        if (synonyms != null && !synonyms.isEmpty()) {
            commonFilters.add("synonym_filter");
        }
        if (stopwords != null && !stopwords.isEmpty()) {
            commonFilters.add("stopword_filter");
        }
        commonFilters.add("nori_readingform");

        IndexSettings.Builder builder = new IndexSettings.Builder()
                .numberOfShards(String.valueOf(numberOfShards))
                .numberOfReplicas(String.valueOf(numberOfReplicas))
                .analysis(a -> {
                    // 1. 필터 정의
                    if (synonyms != null && !synonyms.isEmpty()) {
                        a.filter("synonym_filter", tf -> tf
                                .definition(tfd -> tfd.synonym(syn -> syn
                                        .synonyms(synonyms)
                                        .tokenizer("whitespace")
                                        .lenient(true)
                                )));
                    }
                    if (stopwords != null && !stopwords.isEmpty()) {
                        a.filter("stopword_filter", tf -> tf
                                .definition(tfd -> tfd.stop(stop -> stop.stopwords(stopwords))));
                    }
                    
                    // 2. 토크나이저 정의
                    a.tokenizer("nori_tokenizer_mixed", t -> t
                            .definition(td -> td
                                    .noriTokenizer(nt -> {
                                        nt.decompoundMode(NoriDecompoundMode.Discard); // Mixed -> Discard로 변경
                                        if (dictionary != null && !dictionary.isEmpty()) {
                                            nt.userDictionaryRules(dictionary);
                                        }
                                        return nt;
                                    })
                            )
                    );
                    
                    // 3. 분석기 정의
                    
                    // 3-1. 인덱싱용 분석기 (nori_custom): 길이 제한 없음 (모든 텀 저장)
                    a.analyzer("nori_custom", an -> an
                            .custom(ca -> ca
                                    .tokenizer("nori_tokenizer_mixed")
                                    .filter(commonFilters)
                            )
                    );
                    
                    return a;
                });

        if (vectorFieldConfig.hasVectorField(definition.getIndexName()) || hasKnnVector(definition.getFields())) {
            builder.knn(true);
        }
        return builder.build();
    }

    /**
     * 필드 매핑을 OpenSearch Property로 변환
     */
    private Map<String, Property> createMappingProperties(List<FieldDefinition> fields, String indexName) {
        Map<String, Property> properties = new HashMap<>();

        for (FieldDefinition field : fields) {
            String fieldName = field.getEffectiveFieldName();
            Property property = convertToProperty(field);
            properties.put(fieldName, property);
        }

        // YAML 설정의 벡터 필드 추가
        if (!"file".equalsIgnoreCase(indexName) && vectorFieldConfig.hasVectorField(indexName)) {
            VectorFieldConfig.VectorField vectorField = vectorFieldConfig.getVectorField(indexName);
            Property vectorProperty = Property.of(p -> p.knnVector(knn -> 
                knn.dimension(vectorField.getDimension())
                   .method(method -> method
                       .name("hnsw")
                       .spaceType(vectorField.getSpaceType())
                       .engine(vectorField.getEngine())
                   )
            ));
            properties.put(vectorField.getTargetField(), vectorProperty);
        }

        return properties;
    }

    /**
     * FieldDefinition을 OpenSearch Property로 변환
     */
    private Property convertToProperty(FieldDefinition field) {
        return switch (field.getType()) {
            case TEXT -> Property.of(p -> p.text(t -> {
                if (field.getAnalyzer() != null) {
                    if ("nori".equals(field.getAnalyzer())) {
                        // 인덱싱과 검색 모두 nori_custom 사용 (1글자 검색 허용, 하이라이팅 일치)
                        t.analyzer("nori_custom");
                    } else {
                        t.analyzer(field.getAnalyzer());
                    }
                }
                return t;
            }));
            case KEYWORD -> Property.of(p -> p.keyword(k -> k));
            case INTEGER -> Property.of(p -> p.integer(i -> i));
            case LONG -> Property.of(p -> p.long_(l -> l));
            case DOUBLE -> Property.of(p -> p.double_(d -> d));
            case FLOAT -> Property.of(p -> p.float_(f -> f));
            case BOOLEAN -> Property.of(p -> p.boolean_(b -> b));
            case DATE -> Property.of(p -> p.date(d -> d));
            case KNN_VECTOR -> Property.of(p -> p.knnVector(knn -> {
                int dimension = field.getDimension() != null ? field.getDimension() : 768;
                return knn.dimension(dimension)
                          .method(method -> method
                              .name("hnsw")
                              .engine("faiss") // 엔진을 lucene -> faiss로 변경
                              .spaceType("innerproduct") // 코사인 유사도는 faiss에서 innerproduct 권장 (정규화된 벡터 가정)
                              .parameters(Map.of(
                                  "m", JsonData.of(16),
                                  "ef_construction", JsonData.of(128)
                              ))
                          );
            }));
            case NESTED -> Property.of(p -> p.nested(n -> {
                if (field.getSubFields() != null) {
                    Map<String, Property> subProps = new HashMap<>();
                    for (FieldDefinition sub : field.getSubFields()) {
                        subProps.put(sub.getEffectiveFieldName(), convertToProperty(sub));
                    }
                    n.properties(subProps);
                }
                return n;
            }));
            default -> Property.of(p -> p.text(t -> t));
        };
    }

    /**
     * Bulk 인덱싱 결과
     */
    public record BulkIndexResult(long successCount, long failCount, boolean hasErrors) {
    }
}