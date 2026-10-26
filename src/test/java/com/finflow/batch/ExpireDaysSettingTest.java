package com.finflow.batch;

import com.finflow.dto.batch.BatchRunResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static com.finflow.batch.BatchFixtures.application;
import static com.finflow.batch.BatchFixtures.nowUtc;
import static com.finflow.batch.BatchFixtures.status;
import static org.assertj.core.api.Assertions.assertThat;

// 만료 기간 설정 (APPLICATION_EXPIRE_DAYS)
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.batch.expire-days=10")
class ExpireDaysSettingTest {

    @Autowired
    BatchRunner batchRunner;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("[TC-BATCH-104] 만료 기간을 10일로 설정하면 접수 후 10일 지난 접수가 만료, 9일은 그대로")
    void expireDaysSetting() {
        LocalDateTime now = nowUtc();
        long tenDays = application(jdbc, "DIDIMDOL", "RECEIVED", now.minusDays(10).minusMinutes(1));
        long nineDays = application(jdbc, "DIDIMDOL", "RECEIVED", now.minusDays(9));

        BatchRunResult result = batchRunner.runExpire();

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(status(jdbc, tenDays)).isEqualTo("EXPIRED");
        assertThat(status(jdbc, nineDays)).isEqualTo("RECEIVED");
    }
}
