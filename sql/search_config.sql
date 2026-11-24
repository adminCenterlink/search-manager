-- 검색 설정 테이블(search-manager). 상담AP 관리 화면이 /api/v1/config API로 조회·저장한다.
--
-- ⚠ 대상은 contact_intelligence 스키마(wiki.datasource 접속 대상). 전문검색만 설치할 때도 만든다.
-- ⚠ 상담AP의 TB_CONFIG는 읽기만 한다(아래 이관 SELECT). TB_CONFIG에는 쓰지 않는다.
-- 키는 옛 TB_CONFIG의 KEY_PATH 그대로. 행이 없으면 코드 기본값을 쓴다
-- (Collection.*=N, Fields=빈 값→TITLE·CONTENTS, Weight=0.5, Size=10, 사전 3종=빈 값).
-- 목록형 값(Synonyms·StopWords·Dictionary)은 한 줄에 한 항목.

CREATE TABLE tb_search_config (
    config_key   VARCHAR(100)  NOT NULL COMMENT '설정 키(옛 TB_CONFIG KEY_PATH, 예: System.SearchEngine.Size)',
    config_value VARCHAR(4000) NOT NULL COMMENT '설정 값(문자열). 목록형은 한 줄에 한 항목',
    mdfr_id      VARCHAR(50)   NULL     COMMENT '최종 수정자(상담AP가 넘기는 사용자 식별자)',
    reg_dt       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '최초 등록 시각',
    mdf_dt       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '최종 수정 시각',
    PRIMARY KEY (config_key)
) COMMENT='검색 설정(search-manager)';

-- 이관 — TB_CONFIG의 검색 설정 11행(Collection 5 + Fields·Weight·Size + 사전 3종)을 한 번 복사한다.
-- ⚠ callcenter_verona는 개발 서버의 상담AP DB 이름이다. 설치마다 상담AP DB 이름으로 바꾼다.
-- ⚠ 두 스키마가 같은 MariaDB 서버에 있어야 한다. 다르면 확인 쿼리 결과를 INSERT 문으로 옮긴다.
-- 사전 3종은 옛 구조에서 DESCRIPTION 컬럼에, 나머지는 CONFIG_VALUE에 있었다.
-- 옛 search-manager와 같이 COMPANY_CD·USE_YN·DEL_YN은 보지 않는다.
INSERT IGNORE INTO tb_search_config (config_key, config_value)
SELECT KEY_PATH,
       COALESCE(CASE WHEN KEY_PATH IN ('System.SearchEngine.Synonyms',
                                       'System.SearchEngine.StopWords',
                                       'System.SearchEngine.Dictionary')
                     THEN DESCRIPTION ELSE CONFIG_VALUE END, '')
FROM callcenter_verona.TB_CONFIG
WHERE KEY_PATH IN ('System.SearchEngine.Collection.Call',
                   'System.SearchEngine.Collection.Manual',
                   'System.SearchEngine.Collection.Notice',
                   'System.SearchEngine.Collection.File',
                   'System.SearchEngine.Collection.Wiki',
                   'System.SearchEngine.Fields',
                   'System.SearchEngine.Weight',
                   'System.SearchEngine.Size',
                   'System.SearchEngine.Synonyms',
                   'System.SearchEngine.StopWords',
                   'System.SearchEngine.Dictionary');

-- 확인 — 이관 전후 값이 같은지 본다
-- SELECT config_key, CHAR_LENGTH(config_value) AS len, LEFT(config_value, 60) AS head FROM tb_search_config ORDER BY config_key;
-- SELECT KEY_PATH, CHAR_LENGTH(COALESCE(CASE WHEN KEY_PATH LIKE '%Synonyms' OR KEY_PATH LIKE '%StopWords' OR KEY_PATH LIKE '%Dictionary' THEN DESCRIPTION ELSE CONFIG_VALUE END, '')) AS len
--   FROM callcenter_verona.TB_CONFIG WHERE KEY_PATH LIKE 'System.SearchEngine.%' ORDER BY KEY_PATH;
