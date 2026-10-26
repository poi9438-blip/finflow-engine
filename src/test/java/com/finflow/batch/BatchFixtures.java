package com.finflow.batch;

import com.finflow.util.EncryptionUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

// 배치 테스트용 데이터 (시각을 정확히 정하려고 SQL로 직접 넣음, 시각은 모두 UTC)
final class BatchFixtures {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    // 연소득·DTI 칸은 엔티티로 읽을 때 복호화하므로 테스트 키로 만든 진짜 암호문을 넣음 (만료 배치가 엔티티로 읽음)
    private static final EncryptionUtil ENCRYPTION = testEncryption();

    private static EncryptionUtil testEncryption() {
        EncryptionUtil util = new EncryptionUtil();
        ReflectionTestUtils.setField(util, "encryptionKeyConfig", "FinFlow-AES-256-Key-Test-Padded!");
        return util;
    }

    private BatchFixtures() {
    }

    static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    // 신청 한 건 (연소득 60000000, DTI 0.3041 암호문), DB id 반환
    static long application(JdbcTemplate jdbc, String productCode, String status, LocalDateTime receivedAt) {
        String applicationId = UUID.randomUUID().toString();
        jdbc.update("insert into applications (application_id, type, product_code, applicant_ref, requested_amount,"
                + " term_months, house_price, household_annual_income, dti, first_home, newlywed,"
                + " newborn_within_2_years, child_count, status, received_at, updated_at)"
                + " values (?, 'LOAN', ?, ?, 1, 12, 1, ?, ?, true, false, false, 0, ?, ?, ?)",
                applicationId, productCode, "homefin:" + SEQ.incrementAndGet(), ENCRYPTION.encrypt("60000000"),
                ENCRYPTION.encrypt("0.3041"), status, receivedAt, receivedAt);
        return jdbc.queryForObject("select id from applications where application_id = ?", Long.class, applicationId);
    }

    // 상태 이력 한 줄
    static void history(JdbcTemplate jdbc, long applicationId, String toStatus, LocalDateTime createdAt) {
        jdbc.update("insert into application_status_history (application_id, to_status, actor_type, actor_id,"
                + " created_at) values (?, ?, 'ADMIN', 'operator:1', ?)", applicationId, toStatus, createdAt);
    }

    static String status(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("select status from applications where id = ?", String.class, id);
    }

    static long historyRows(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("select count(*) from application_status_history where application_id = ?",
                Long.class, id);
    }
}
