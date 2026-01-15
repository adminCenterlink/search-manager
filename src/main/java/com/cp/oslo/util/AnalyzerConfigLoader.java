package com.cp.oslo.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import java.util.stream.Collectors; // Collectors import

/**
 * 검색 엔진의 동의어(synonyms)와 불용어(stopwords) 설정 파일을 로드하는 유틸리티 클래스
 * (TB_CONFIG 테이블 기반)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyzerConfigLoader {
    
    private final JdbcTemplate jdbcTemplate;
    
    private static final String SYNONYMS_KEY_PATH = "System.SearchEngine.Synonyms";
    private static final String STOPWORDS_KEY_PATH = "System.SearchEngine.StopWords";
    private static final String DICTIONARY_KEY_PATH = "System.SearchEngine.Dictionary";
    
    /**
     * 동의어 목록을 로드합니다.
     * 
     * @return 동의어 목록 (각 줄이 하나의 동의어 규칙)
     */
    public List<String> loadSynonyms() {
        List<String> rawSynonyms = loadFromDatabase(SYNONYMS_KEY_PATH, "동의어");
        
        return rawSynonyms.stream()
                .map(line -> {
                    try {
                        // 1. 매핑형 규칙 (A => B) 정규화
                        if (line.contains("=>")) {
                            String[] parts = line.split("=>");
                            if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
                                return parts[0].trim() + " => " + parts[1].trim();
                            }
                        } 
                        // 2. 나열형 규칙 (A, B, C) 정규화
                        else if (line.contains(",")) {
                            String normalized = Arrays.stream(line.split(","))
                                    .map(String::trim)
                                    .filter(s -> !s.isEmpty())
                                    .collect(Collectors.joining(", "));
                            
                            // 최소 2개 단어가 있어야 유효
                            if (normalized.contains(",")) {
                                return normalized;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("동의어 규칙 정규화 실패: {}", line);
                    }
                    return null; // 유효하지 않으면 null 반환
                })
                .filter(line -> line != null) // null 제거
                .collect(Collectors.toList());
    }
    
    /**
     * 불용어 목록을 로드합니다.
     * 
     * @return 불용어 목록
     */
    public List<String> loadStopwords() {
        return loadFromDatabase(STOPWORDS_KEY_PATH, "불용어");
    }

    /**
     * 사용자 사전 목록을 로드합니다.
     * 
     * @return 사용자 사전 목록 (보냉백 보냉 백)
     */
    public List<String> loadUserDictionary() {
        return loadFromDatabase(DICTIONARY_KEY_PATH, "사용자 사전");
    }

    /**
     * 하이브리드 검색 대상 필드 목록을 로드합니다. (System.SearchEngine.Fields)
     */
    public List<String> loadSearchFields() {
        String value = loadConfigValue("System.SearchEngine.Fields");
        if (value == null || value.isEmpty()) return Collections.emptyList();
        // 쉼표로 분리하고 공백 제거 및 대문자 변환 (인덱스 필드명과 일치시키기 위해)
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(Collectors.toList());
    }

    /**
     * 하이브리드 검색 텍스트 가중치를 로드합니다. (System.SearchEngine.Weight)
     */
    public Double loadSearchWeight() {
        String value = loadConfigValue("System.SearchEngine.Weight");
        try {
            return value != null ? Double.parseDouble(value) : 0.5; // 기본값 0.5
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }

    /**
     * 하이브리드 검색 결과 개수를 로드합니다. (System.SearchEngine.Size)
     */
    public Integer loadSearchSize() {
        String value = loadConfigValue("System.SearchEngine.Size");
        try {
            return value != null ? Integer.parseInt(value) : 10; // 기본값 10
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    /**
     * TB_CONFIG 테이블의 CONFIG_VALUE 컬럼 값을 조회합니다.
     */
    private String loadConfigValue(String keyPath) {
        try {
            // EmptyResultDataAccessException 처리를 위해 list로 조회
            String sql = "SELECT CONFIG_VALUE FROM TB_CONFIG WHERE KEY_PATH = ?";
            List<String> results = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("CONFIG_VALUE"), keyPath);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            log.error("설정 값 로드 실패: {}", keyPath, e);
            return null;
        }
    }
    
    /**
     * 데이터베이스에서 설정값을 읽어서 목록으로 반환합니다.
     * TB_CONFIG 테이블의 DESCRIPTION 컬럼을 사용합니다.
     * 주석(#으로 시작하는 줄)과 빈 줄은 무시합니다.
     * 
     * @param keyPath TB_CONFIG의 KEY_PATH
     * @param description 설정 설명 (로깅용)
     * @return 설정 내용 목록
     */
    private List<String> loadFromDatabase(String keyPath, String description) {
        List<String> resultList = new ArrayList<>();
        
        try {
            String sql = "SELECT DESCRIPTION FROM TB_CONFIG WHERE KEY_PATH = ?";
            List<String> results = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("DESCRIPTION"), keyPath);
            
            if (results.isEmpty() || results.get(0) == null) {
                log.warn("{} 설정이 TB_CONFIG에 존재하지 않거나 값이 비어있습니다: {}", description, keyPath);
                return resultList;
            }
            
            String content = results.get(0);
            // 줄바꿈 문자로 분리 (\r\n, \n, \r 모두 처리)
            String[] lines = content.split("\\R");
            
            for (String line : lines) {
                // 공백 제거
                line = line.trim();
                
                // 빈 줄이나 주석은 무시
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                resultList.add(line);
            }
            
            log.info("{} 로드 완료 (DB): {} ({}개 항목)", description, keyPath, resultList.size());
            
        } catch (Exception e) {
            log.error("{} 로드 실패 (DB): {}", description, keyPath, e);
            // DB 조회 실패 시 빈 리스트 반환 (또는 예외 던지기 선택 가능)
            // 여기서는 안전하게 빈 리스트 반환
        }
        
        return resultList;
    }
}
