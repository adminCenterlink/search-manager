package com.cp.oslo.scheduler;

import com.cp.oslo.service.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * wiki 인덱스 전용 스케줄러
 * 전역 동기화(01시)와 분리한다 — wiki는 contact-intelligence가 야간에 전량 재생성한 뒤 색인해야 한다.
 * 활성/비활성 판단과 store DB 접속 여부는 IndexingService.syncIndex가 처리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WikiSyncScheduler {

    private final IndexingService indexingService;

    @Scheduled(cron = "${search.indexes.wiki.cron:0 0 5 * * *}")
    public void runWikiSync() {
        log.info("스케줄러에 의한 wiki 인덱스 동기화 시작 (Time: {})", LocalDateTime.now());
        try {
            indexingService.syncIndex("wiki");
        } catch (Exception e) {
            log.error("스케줄러 wiki 동기화 중 오류 발생", e);
        }
    }
}
