package com.finflow.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// 자동 실행 시각 단위 테스트 (실제로 03시까지 기다리지 않고 cron 계산으로 확인)
class BatchScheduleTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("[TC-BATCH-201] 자동 실행: 만료는 한국 시각 매일 03:00, 집계는 03:30 (cron·zone이 메서드에 그대로 붙음), 집계 대상은 한국 날짜 어제, 한국 하루 = UTC 전날 15:00 ~ 당일 15:00")
    void schedule() throws Exception {
        Scheduled expire = BatchScheduler.class.getMethod("expire").getAnnotation(Scheduled.class);
        Scheduled stats = BatchScheduler.class.getMethod("dailyStats").getAnnotation(Scheduled.class);
        assertThat(expire.cron()).isEqualTo(BatchScheduler.EXPIRE_CRON);
        assertThat(stats.cron()).isEqualTo(BatchScheduler.DAILY_STATS_CRON);
        assertThat(expire.zone()).isEqualTo("Asia/Seoul");
        assertThat(stats.zone()).isEqualTo("Asia/Seoul");

        // 한국 시각 10월 27일 00:10 이후 다음 실행
        ZonedDateTime base = ZonedDateTime.of(2026, 10, 27, 0, 10, 0, 0, SEOUL);
        assertThat(CronExpression.parse(expire.cron()).next(base))
                .isEqualTo(ZonedDateTime.of(2026, 10, 27, 3, 0, 0, 0, SEOUL));
        assertThat(CronExpression.parse(stats.cron()).next(base))
                .isEqualTo(ZonedDateTime.of(2026, 10, 27, 3, 30, 0, 0, SEOUL));

        assertThat(BatchScheduler.yesterdayInKorea()).isEqualTo(LocalDate.now(SEOUL).minusDays(1));
        LocalDateTime[] range = DailyStatsAggregator.utcRange(LocalDate.of(2026, 10, 26));
        assertThat(range[0]).isEqualTo(LocalDateTime.of(2026, 10, 25, 15, 0));
        assertThat(range[1]).isEqualTo(LocalDateTime.of(2026, 10, 26, 15, 0));
    }
}
