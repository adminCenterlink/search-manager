package com.cp.oslo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * application.yml의 인덱스 설정 정보를 매핑하는 클래스
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "search")
public class SearchIndexProperties {

    private Sync sync;
    private Map<String, IndexSettings> indexes;

    @Data
    public static class Sync {
        private String cron;
    }

    @Data
    public static class IndexSettings {
        private boolean enabled = true;
        private boolean autoSync = true;
        private int syncIntervalMinutes = 60;
        private String description;
        private String localPathPrefix; // 추가: 파일 경로 매핑 접두어
        private Integer defaultSearchSize; // 추가: 기본 검색 결과 크기
        private boolean ocrEnabled; // OCR 활성화 여부
        private List<String> targetSrcIds; // 인덱싱 대상 SRC_ID 목록
        private String cron; // 전용 스케줄(설정 시 전역 동기화에서 제외)
    }
}
