package com.cp.oslo.config;

import com.cp.oslo.model.FieldDefinition;
import com.cp.oslo.model.IndexDefinition;
import com.cp.oslo.model.FieldDefinition.FieldType;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor; // Import added
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 인덱스 정의 저장소
 * 코드 레벨에서 인덱스 구조(필드 매핑 등)를 관리합니다.
 */
@Component
@RequiredArgsConstructor // Annotation added
public class IndexRegistry {

    private final VectorFieldConfig vectorFieldConfig; // Dependency injection
    @Getter
    private final Map<String, IndexDefinition> definitions = new HashMap<>();

    @PostConstruct
    public void init() {
        // 1. Manual 인덱스 정의
        definitions.put("manual", IndexDefinition.builder()
                .indexName("manual")
                .sourceTableName("uvw_manual")
                .description("매뉴얼")
                .idColumn("MANUAL_UUID")
                .fields(List.of(
                        field("MANUAL_UUID", FieldType.KEYWORD),
                        field("TITLE", FieldType.TEXT, "nori"),
                        field("CONTENTS", FieldType.TEXT, "nori"),
                        field("CAT_NM", FieldType.KEYWORD),
                        field("REG_DT", FieldType.DATE)
                        // 필요한 필드들 추가
                ))
                .build());

        // 2. Manual QnA 인덱스 정의
        definitions.put("manual-qna", IndexDefinition.builder()
                .indexName("manual-qna")
                .sourceTableName("uvw_manual_qna")
                .description("QnA")
                .idColumn("MANUAL_UUID")
                .fields(List.of(
                        field("MANUAL_UUID", FieldType.KEYWORD),
                        field("TITLE", FieldType.TEXT, "nori"),
                        field("CONTENTS", FieldType.TEXT, "nori")
                ))
                .build());

        // 3. Call 인덱스 정의
        definitions.put("call", IndexDefinition.builder()
                .indexName("call")
                .sourceTableName("uvw_call")
                .description("상담이력")
                .idColumn("CALL_UUID")
                .fields(List.of(
                        field("CALL_UUID", FieldType.KEYWORD),
                        field("FULL_CALL_CAT_NM", FieldType.TEXT, "nori"),
                        field("QUESTION", FieldType.TEXT, "nori"),
                        field("ANSWER", FieldType.TEXT, "nori"),
//                        field("MEMO", FieldType.TEXT, "nori"),
                        field("FULL_DEPT_NM", FieldType.TEXT, "nori"),
                        field("CALL_TYPE_NM", FieldType.TEXT, "nori"),
                        field("RGTR_NM", FieldType.TEXT, "nori"),
                        field("CALL_DT", FieldType.DATE)
                ))
                .build());

        // 4. Notice 인덱스 정의
        definitions.put("notice", IndexDefinition.builder()
                .indexName("notice")
                .sourceTableName("uvw_doc_notice") // DB 뷰 이름은 uvw_doc_notice 유지
                .description("공지사항")
                .idColumn("DOC_UUID") // DB 컬럼명 DOC_UUID 유지
                .fields(List.of(
                        field("DOC_UUID", FieldType.KEYWORD), // DB 컬럼명 DOC_UUID 유지
                        field("DOC_NM", FieldType.TEXT, "nori"), // DB 컬럼명 DOC_NM 유지
                        field("CONTENTS", FieldType.TEXT, "nori"),
                        field("REG_DT", FieldType.DATE)
                ))
                .build());

        // 5. Integrated Search 인덱스 정의 (UVW_SEARCH)
        definitions.put("unified", IndexDefinition.builder()
                .indexName("unified")
                .sourceTableName("UVW_SEARCH")
                .description("통합 검색")
                .idColumn("UUID")
                .fields(List.of(
                        field("DATA_TYPE", FieldType.KEYWORD),
                        field("UUID", FieldType.KEYWORD),
                        field("TITLE", FieldType.TEXT, "nori"),
                        field("CONTENTS", FieldType.TEXT, "nori"),
                        // WIKI 타입 전용(다른 타입은 비움). UVW_SEARCH에 없는 컬럼이라 매핑만 정의한다.
                        mapped("ITEM_NO", FieldType.INTEGER),
                        mapped("CAT_ID", FieldType.INTEGER),
                        mapped("DOC_TITLE", FieldType.TEXT, "nori")
                ))
                .build());

        // 5-1. Wiki 인덱스 정의 — tb_wiki_item 1행 = 문서 1건(항목 단위 원자 색인).
        // 원본은 contact-intelligence store DB(WikiDataSource)라 조회 SQL은 IndexingService가 직접 쓴다.
        // TITLE=topic(소절 제목), CONTENTS=item_text — 기존 hybrid/rerank 경로가 이 두 이름을 하드코딩한다.
        definitions.put("wiki", IndexDefinition.builder()
                .indexName("wiki")
                .sourceTableName("tb_wiki_item")
                .description("LLM 위키 항목")
                .idColumn("ITEM_ID")
                .fields(List.of(
                        mapped("ITEM_ID", FieldType.KEYWORD),        // doc_uuid:item_no
                        mapped("DOC_UUID", FieldType.KEYWORD),       // =상담분류 CAT_UUID
                        mapped("CAT_ID", FieldType.INTEGER),
                        mapped("ITEM_NO", FieldType.INTEGER),
                        mapped("CHAPTER_NO", FieldType.INTEGER),
                        mapped("SECTION_KEY", FieldType.KEYWORD),
                        mapped("EVIDENCE_KEY", FieldType.KEYWORD),   // 재생성을 가로지르는 항목 앵커
                        mapped("MANUAL_UUID", FieldType.KEYWORD),
                        mapped("MANUAL_LABEL", FieldType.KEYWORD),
                        mapped("TITLE", FieldType.TEXT, "nori"),
                        mapped("CONTENTS", FieldType.TEXT, "nori"),
                        mapped("DOC_TITLE", FieldType.TEXT, "nori"),
                        mapped("CHAPTER_LABEL", FieldType.TEXT, "nori"),
                        mapped("FULL_CAT_NM", FieldType.TEXT, "nori"),
                        mapped("GEN_NO", FieldType.INTEGER),
                        mapped("GEN_DT", FieldType.DATE)
                ))
                .build());

        // 6. File 인덱스 정의
        int fileDimension = 768; // 기본값을 768로 변경 (하드코딩)
        
        // 설정값이 있으면 덮어쓰기 (단, 1024가 들어올 수 있으므로 주의)
        if (vectorFieldConfig.getVectorField("file") != null) {
            Integer dim = vectorFieldConfig.getVectorField("file").getDimension();
            if (dim != null && dim > 0) {
                fileDimension = dim;
            } else if (vectorFieldConfig.getDimension() != null) {
                // 전역 설정이 1024라면 여기서 1024가 됨. application.yml의 embedding.dimension을 확인해야 함.
                fileDimension = vectorFieldConfig.getDimension();
            }
        }
        
        definitions.put("file", IndexDefinition.builder()
                .indexName("file")
                .sourceTableName("TB_FILE")
                .description("첨부파일")
                .idColumn("FILE_ID")
                .fields(List.of(
                        field("FILE_ID", FieldType.KEYWORD),
                        field("FILE_NM", FieldType.TEXT, "nori"),
                        field("SAVED_FILE_PATH", FieldType.KEYWORD),
                        field("FILE_UUID", FieldType.KEYWORD),
                        field("URL", FieldType.KEYWORD),
                        field("CONTENT_TYPE", FieldType.KEYWORD),
                        field("REG_DT", FieldType.DATE),
                        FieldDefinition.builder()
                                .targetField("paragraphs")
                                .type(FieldType.NESTED)
                                .indexed(true)
                                .subFields(List.of(
                                        field("content", FieldType.TEXT, "nori"),
                                        FieldDefinition.builder()
                                                .targetField("embedding")
                                                .type(FieldType.KNN_VECTOR)
                                                .dimension(fileDimension)
                                                .indexed(true)
                                                .build()
                                ))
                                .build()
                ))
                .build());
    }

    public IndexDefinition get(String indexName) {
        return definitions.get(indexName);
    }

    // 헬퍼 메서드
    private FieldDefinition field(String source, FieldType type) {
        return FieldDefinition.builder().sourceColumn(source).type(type).indexed(true).build();
    }

    private FieldDefinition field(String source, FieldType type, String analyzer) {
        return FieldDefinition.builder().sourceColumn(source).type(type).indexed(true).analyzer(analyzer).build();
    }

    // 매핑 전용 필드 — 원본 컬럼이 없어 범용 SELECT 경로가 건너뛴다(sourceColumn=null)
    private FieldDefinition mapped(String target, FieldType type) {
        return FieldDefinition.builder().targetField(target).type(type).indexed(true).build();
    }

    private FieldDefinition mapped(String target, FieldType type, String analyzer) {
        return FieldDefinition.builder().targetField(target).type(type).indexed(true).analyzer(analyzer).build();
    }
}
