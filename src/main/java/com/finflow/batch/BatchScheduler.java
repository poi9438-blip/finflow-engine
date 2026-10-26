package com.finflow.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;

// 자동 실행 (한국 시각 매일 03:00 만료, 03:30 전날 집계)
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.batch.scheduling-enabled", havingValue = "true")
@RequiredArgsConstructor
public class BatchScheduler {

    public static final String ZONE = "Asia/Seoul";
    public static final String EXPIRE_CRON = "0 0 3 * * *";
    public static final String DAILY_STATS_CRON = "0 30 3 * * *";

    private final BatchRunner batchRunner;

    @Scheduled(cron = EXPIRE_CRON, zone = ZONE)
    public void expire() {
        runSafely(batchRunner::runExpire);
    }

    @Scheduled(cron = DAILY_STATS_CRON, zone = ZONE)
    public void dailyStats() {
        runSafely(() -> batchRunner.runDailyStats(yesterdayInKorea()));
    }

    // 한국 날짜 기준 어제
    static LocalDate yesterdayInKorea() {
        return LocalDate.now(DailyStatsAggregator.KOREA).minusDays(1);
    }

    // 자동 실행은 응답을 받을 곳이 없으므로 실패를 로그로만 남김 (다음 실행이나 운영자 수동 실행으로 다시 처리)
    private static void runSafely(Runnable job) {
        try {
            job.run();
        } catch (RuntimeException e) {
            log.error("Scheduled batch failed: {}", e.getMessage());
        }
    }
}
