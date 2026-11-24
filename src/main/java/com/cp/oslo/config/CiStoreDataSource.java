package com.cp.oslo.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * contact-intelligence store DB 접속 — wiki 인덱스 원본과 검색 설정(tb_search_config).
 * DataSource/JdbcTemplate을 Spring 빈으로 등록하지 않는다 — 등록하면 기본 DataSource 자동설정이 물러나
 * JPA와 주 JdbcTemplate이 깨진다. 이 홀더가 커넥션 풀을 직접 소유한다.
 * url이 비어 있으면 비활성 상태로 기동한다 — wiki 색인은 건너뛰고 검색 설정은 기본값만 쓴다.
 * 전문검색만 설치할 때도 검색 설정을 쓰려면 설정해야 한다.
 */
@Component
@Slf4j
public class CiStoreDataSource {

    @Value("${ci-store.datasource.url:}")
    private String url;
    @Value("${ci-store.datasource.username:}")
    private String username;
    @Value("${ci-store.datasource.password:}")
    private String password;
    @Value("${ci-store.datasource.driver-class-name:org.mariadb.jdbc.Driver}")
    private String driverClassName;

    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    void init() {
        if (url == null || url.isBlank()) {
            log.warn("ci-store.datasource.url 미설정 — wiki 색인 비활성, 검색 설정은 기본값만 사용");
            return;
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        config.setPoolName("ci-store-pool");
        config.setMaximumPoolSize(3); // 야간 배치 조회 + 검색 설정 조회
        config.setMinimumIdle(0);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        dataSource = new HikariDataSource(config);
        jdbcTemplate = new JdbcTemplate(dataSource);
        log.info("CI store DataSource 초기화: {}", url);
    }

    @PreDestroy
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    public boolean isAvailable() {
        return jdbcTemplate != null;
    }

    /** 미설정 상태에서 호출하면 예외. 호출 전 isAvailable()로 확인한다. */
    public JdbcTemplate jdbc() {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("CI store DataSource가 설정되지 않았습니다 (ci-store.datasource.url)");
        }
        return jdbcTemplate;
    }
}
