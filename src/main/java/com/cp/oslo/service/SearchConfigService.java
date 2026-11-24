package com.cp.oslo.service;

import com.cp.oslo.config.CiStoreDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 검색 설정(contact_intelligence.tb_search_config) 조회·저장.
 * 키는 옛 TB_CONFIG의 KEY_PATH를 그대로 쓴다. 행이 없으면 기본값을 쓴다.
 * 접속은 CiStoreDataSource(contact_intelligence)를 wiki 색인과 공유한다 — 미설정이면 기본값으로만 동작한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchConfigService {

    public static final String COLLECTION_PREFIX = "System.SearchEngine.Collection.";
    public static final String FIELDS = "System.SearchEngine.Fields";
    public static final String WEIGHT = "System.SearchEngine.Weight";
    public static final String SIZE = "System.SearchEngine.Size";
    public static final String SYNONYMS = "System.SearchEngine.Synonyms";
    public static final String STOPWORDS = "System.SearchEngine.StopWords";
    public static final String DICTIONARY = "System.SearchEngine.Dictionary";

    private static final int MAX_VALUE_LENGTH = 4000; // config_value 컬럼 길이

    /** 값이 맞지 않으면 사유를, 맞으면 null을 돌려준다. */
    private record Spec(String defaultValue, Function<String, String> validator) {}

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        for (String type : List.of("Call", "Manual", "Notice", "File", "Wiki")) {
            SPECS.put(COLLECTION_PREFIX + type, new Spec("N", SearchConfigService::validateYn));
        }
        // 빈 값이면 검색 API가 TITLE, CONTENTS를 쓴다(SearchController)
        SPECS.put(FIELDS, new Spec("", v -> null));
        SPECS.put(WEIGHT, new Spec("0.5", SearchConfigService::validateWeight));
        SPECS.put(SIZE, new Spec("10", SearchConfigService::validateSize));
        SPECS.put(SYNONYMS, new Spec("", v -> null));
        SPECS.put(STOPWORDS, new Spec("", v -> null));
        SPECS.put(DICTIONARY, new Spec("", v -> null));
    }

    private final CiStoreDataSource dataSource;

    /** 등록되지 않은 키 → 404 */
    public static class UnknownKeyException extends RuntimeException {
        public UnknownKeyException(String key) { super(key); }
    }

    /** 값 검증 실패 → 400 */
    public static class InvalidValueException extends RuntimeException {
        public InvalidValueException(String message) { super(message); }
    }

    /** DB 미설정 → 503 */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException() { super("설정 DB가 설정되지 않았습니다"); }
    }

    // ── 검색·색인 코드가 쓰는 조회 ───────────────────────

    /** 저장된 값, 없으면 기본값. DB 조회가 실패해도 기본값을 돌려준다(검색을 막지 않는다). */
    public String getValue(String key) {
        Spec spec = SPECS.get(key);
        String fallback = spec != null ? spec.defaultValue() : null;
        if (!dataSource.isAvailable()) {
            return fallback;
        }
        try {
            List<String> rows = dataSource.jdbc().query(
                    "SELECT config_value FROM tb_search_config WHERE config_key = ?",
                    (rs, rowNum) -> rs.getString(1), key);
            return rows.isEmpty() ? fallback : rows.get(0);
        } catch (Exception e) {
            log.error("검색 설정 조회 실패 — 기본값 사용: {}", key, e);
            return fallback;
        }
    }

    /** Collection 키 중 Y인 타입(대문자, 예: CALL). */
    public List<String> enabledCollections() {
        List<String> types = new ArrayList<>();
        for (String key : SPECS.keySet()) {
            if (key.startsWith(COLLECTION_PREFIX) && "Y".equalsIgnoreCase(getValue(key))) {
                types.add(key.substring(COLLECTION_PREFIX.length()).toUpperCase());
            }
        }
        return types;
    }

    // ── 관리 API ─────────────────────────────────────────

    public List<Map<String, String>> list() {
        Map<String, String> stored = loadAll();
        List<Map<String, String>> out = new ArrayList<>();
        SPECS.forEach((key, spec) -> out.add(item(key, stored.getOrDefault(key, spec.defaultValue()))));
        return out;
    }

    public Map<String, String> get(String key) {
        spec(key);
        requireAvailable();
        return item(key, getValue(key));
    }

    public Map<String, String> put(String key, String value, String mdfrId) {
        Spec spec = spec(key);
        if (value == null) {
            throw new InvalidValueException("value가 필요합니다");
        }
        value = value.trim();
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new InvalidValueException("값은 " + MAX_VALUE_LENGTH + "자 이하여야 합니다");
        }
        String reason = spec.validator().apply(value);
        if (reason != null) {
            throw new InvalidValueException(reason);
        }
        requireAvailable();
        dataSource.jdbc().update(
                "INSERT INTO tb_search_config (config_key, config_value, mdfr_id) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), mdfr_id = VALUES(mdfr_id)",
                key, value, mdfrId);
        log.info("검색 설정 저장: {} (수정자 {})", key, mdfrId);
        return item(key, value);
    }

    public Map<String, String> delete(String key) {
        Spec spec = spec(key);
        requireAvailable();
        dataSource.jdbc().update("DELETE FROM tb_search_config WHERE config_key = ?", key);
        log.info("검색 설정 기본값 복원: {}", key);
        return item(key, spec.defaultValue());
    }

    private Map<String, String> loadAll() {
        requireAvailable();
        Map<String, String> stored = new LinkedHashMap<>();
        dataSource.jdbc().query("SELECT config_key, config_value FROM tb_search_config",
                rs -> { stored.put(rs.getString(1), rs.getString(2)); });
        return stored;
    }

    private Spec spec(String key) {
        Spec spec = SPECS.get(key);
        if (spec == null) {
            throw new UnknownKeyException(key);
        }
        return spec;
    }

    private void requireAvailable() {
        if (!dataSource.isAvailable()) {
            throw new UnavailableException();
        }
    }

    private static Map<String, String> item(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("value", value);
        return m;
    }

    // ── 검증 — 범위는 검색 API가 잘라 쓰는 범위(SearchController)와 같게 둔다 ──

    private static String validateYn(String v) {
        return "Y".equals(v) || "N".equals(v) ? null : "값은 Y 또는 N이어야 합니다";
    }

    private static String validateWeight(String v) {
        try {
            double d = Double.parseDouble(v);
            return d >= 0.0 && d <= 1.0 ? null : "값은 0 이상 1 이하여야 합니다";
        } catch (NumberFormatException e) {
            return "값은 숫자여야 합니다";
        }
    }

    private static String validateSize(String v) {
        try {
            int n = Integer.parseInt(v);
            return n >= 1 && n <= 100 ? null : "값은 1 이상 100 이하여야 합니다";
        } catch (NumberFormatException e) {
            return "값은 정수여야 합니다";
        }
    }
}
