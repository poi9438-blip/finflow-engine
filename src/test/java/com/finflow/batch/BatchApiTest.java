package com.finflow.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.service.ApplicationService;
import com.finflow.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static com.finflow.batch.BatchFixtures.application;
import static com.finflow.batch.BatchFixtures.history;
import static com.finflow.batch.BatchFixtures.historyRows;
import static com.finflow.batch.BatchFixtures.nowUtc;
import static com.finflow.batch.BatchFixtures.status;
import static com.finflow.support.ApiTestSupport.bearer;
import static com.finflow.support.ApiTestSupport.serviceToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 만료·일별 집계 배치와 배치 API 통합 테스트 (Spring Batch, H2)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BatchApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;
    // 실제 서비스를 감싸서 동시 수정 실패만 일부러 만듦 (TC-BATCH-103)
    @SpyBean
    ApplicationService applicationService;

    private ApiTestSupport api;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiTestSupport(mockMvc, objectMapper);
        adminToken = api.operatorToken();
    }

    private JsonNode runExpire(String traceId) throws Exception {
        return api.read(mockMvc.perform(post("/api/admin/batch/expire").header("Authorization", bearer(adminToken))
                .header("X-Trace-Id", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andReturn());
    }

    private JsonNode stats(String date) throws Exception {
        api.adminPost(adminToken, "/api/admin/batch/daily-stats?date=" + date, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        return api.read(api.adminGet(adminToken, "/api/admin/stats/daily?from=" + date + "&to=" + date)
                .andExpect(status().isOk()).andReturn()).get("data");
    }

    // 상품별 [접수, 승인, 거절, 취소, 만료]
    private static int[] row(JsonNode rows, String productCode) {
        for (JsonNode r : rows) {
            if (r.get("productCode").asText().equals(productCode)) {
                return new int[] { r.get("received").asInt(), r.get("approved").asInt(), r.get("rejected").asInt(),
                        r.get("cancelled").asInt(), r.get("expired").asInt() };
            }
        }
        throw new AssertionError("no row for " + productCode);
    }

    @Test
    @DisplayName("[TC-BATCH-101] 만료 배치: 30일 1분 지난 접수만 EXPIRED(이력 BATCH·applicationExpireJob·batch- traceId), 29일 23시간 59분 접수와 31일 심사 중은 그대로, 응답 traceId는 요청 값으로 되돌림")
    void expire_onlyDueReceived() throws Exception {
        LocalDateTime now = nowUtc();
        long due = application(jdbc, "BOGEUMJARI", "RECEIVED", now.minusDays(30).minusMinutes(1));
        long notYet = application(jdbc, "BOGEUMJARI", "RECEIVED", now.minusDays(30).plusMinutes(1));
        long reviewing = application(jdbc, "BOGEUMJARI", "REVIEWING", now.minusDays(31));

        JsonNode result = runExpire("ff-expire-101").get("data");

        assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("processed").asLong()).isEqualTo(1);
        String batchTrace = result.get("traceId").asText();
        assertThat(batchTrace).startsWith("batch-");
        assertThat(status(jdbc, due)).isEqualTo("EXPIRED");
        assertThat(status(jdbc, notYet)).isEqualTo("RECEIVED");
        assertThat(status(jdbc, reviewing)).isEqualTo("REVIEWING");
        Map<String, Object> h = jdbc.queryForMap("select from_status, to_status, actor_type, actor_id, trace_id"
                + " from application_status_history where application_id = ?", due);
        assertThat(h).containsEntry("FROM_STATUS", "RECEIVED").containsEntry("TO_STATUS", "EXPIRED")
                .containsEntry("ACTOR_TYPE", "BATCH").containsEntry("ACTOR_ID", "applicationExpireJob")
                .containsEntry("TRACE_ID", batchTrace);
    }

    @Test
    @DisplayName("[TC-BATCH-102] 만료 배치를 바로 다시 실행 → 만료 0건, 이력 추가 없음, 실행은 매번 새로(실행 번호가 다름)")
    void expire_rerunIsHarmless() throws Exception {
        long due = application(jdbc, "DIDIMDOL", "RECEIVED", nowUtc().minusDays(40));
        JsonNode first = runExpire("ff-expire-102a").get("data");
        long rows = historyRows(jdbc, due);

        JsonNode second = runExpire("ff-expire-102b").get("data");

        assertThat(second.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(second.get("processed").asLong()).isZero();
        assertThat(second.get("jobExecutionId").asLong()).isNotEqualTo(first.get("jobExecutionId").asLong());
        assertThat(historyRows(jdbc, due)).isEqualTo(rows);
    }

    @Test
    @DisplayName("[TC-BATCH-103] 만료 대상 3건 중 1건이 동시 수정으로 실패 → 그 건만 건너뜀(skipped 1), 나머지 2건 만료, COMPLETED")
    void expire_skipsConcurrentUpdate() throws Exception {
        LocalDateTime old = nowUtc().minusDays(35);
        long a = application(jdbc, "BOGEUMJARI", "RECEIVED", old);
        long b = application(jdbc, "BOGEUMJARI", "RECEIVED", old);
        long c = application(jdbc, "BOGEUMJARI", "RECEIVED", old);
        doThrow(new ObjectOptimisticLockingFailureException("Application", b)).when(applicationService)
                .expireIfDue(eq(b), any(LocalDateTime.class), anyString());

        JsonNode result = runExpire("ff-expire-103").get("data");

        assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("processed").asLong()).isEqualTo(2);
        assertThat(result.get("skipped").asLong()).isEqualTo(1);
        assertThat(status(jdbc, a)).isEqualTo("EXPIRED");
        assertThat(status(jdbc, b)).isEqualTo("RECEIVED");
        assertThat(status(jdbc, c)).isEqualTo("EXPIRED");
        // 다른 테스트의 만료 건수에 섞이지 않게 정리
        jdbc.update("update applications set received_at = ? where id = ?", nowUtc(), b);
    }

    // 읽은 뒤 처리 전에 바뀐 신청은 만료하지 않음 (처리 직전 재확인)
    @Test
    @DisplayName("[TC-BATCH-109] 배치가 읽은 뒤 처리 전에 심사가 시작된 신청은 만료하지 않음 (대상 아님 notDue 1), 나머지는 만료")
    void expire_rechecksBeforeProcessing() throws Exception {
        LocalDateTime old = nowUtc().minusDays(35);
        long first = application(jdbc, "NEWBORN_DIDIMDOL", "RECEIVED", old);
        long second = application(jdbc, "NEWBORN_DIDIMDOL", "RECEIVED", old);
        doAnswer(invocation -> {
            jdbc.update("update applications set status = 'REVIEWING' where id = ?", second);
            return invocation.callRealMethod();
        }).when(applicationService).expireIfDue(eq(first), any(LocalDateTime.class), anyString());

        JsonNode result = runExpire("ff-expire-109").get("data");

        assertThat(result.get("processed").asLong()).isEqualTo(1);
        assertThat(result.get("notDue").asLong()).isEqualTo(1);
        assertThat(status(jdbc, first)).isEqualTo("EXPIRED");
        assertThat(status(jdbc, second)).isEqualTo("REVIEWING");
    }

    @Test
    @DisplayName("[TC-BATCH-110] 동시 수정이 아닌 오류(암호문 손상으로 복호화 실패)는 건너뛰지 않고 배치를 FAILED로 끝냄 → API 500, 원인은 로그에만")
    void expire_otherErrorsFailJob() throws Exception {
        long broken = application(jdbc, "BOGEUMJARI", "RECEIVED", nowUtc().minusDays(35));
        jdbc.update("update applications set household_annual_income = 'not-a-ciphertext' where id = ?", broken);
        try {
            mockMvc.perform(post("/api/admin/batch/expire").header("Authorization", bearer(adminToken)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("Batch job applicationExpireJob FAILED"));
            assertThat(status(jdbc, broken)).isEqualTo("RECEIVED");
        } finally {
            // 다른 테스트의 만료 배치가 이 행 때문에 실패하지 않게 정리
            jdbc.update("update applications set status = 'CANCELLED' where id = ?", broken);
        }
    }

    @Test
    @DisplayName("[TC-BATCH-105] 일별 집계: 한국 날짜 하루(자정 정각·자정 1초 전 포함)만 세고, 상품 3개 모두 한 줄씩(0 포함)")
    void dailyStats_countsKoreanDay() throws Exception {
        // 한국 2020-01-15 = UTC 2020-01-14 15:00 ~ 2020-01-15 15:00
        LocalDateTime start = LocalDateTime.of(2020, 1, 14, 15, 0);
        LocalDateTime end = LocalDateTime.of(2020, 1, 15, 15, 0);
        long first = application(jdbc, "BOGEUMJARI", "APPROVED", start);
        long last = application(jdbc, "BOGEUMJARI", "REJECTED", end.minusSeconds(1));
        long before = application(jdbc, "BOGEUMJARI", "CANCELLED", start.minusSeconds(1));
        long after = application(jdbc, "BOGEUMJARI", "EXPIRED", end);
        history(jdbc, first, "APPROVED", start.plusHours(10));
        history(jdbc, last, "REJECTED", end.minusSeconds(1));
        history(jdbc, before, "CANCELLED", start);
        history(jdbc, after, "EXPIRED", end);
        long didimdol = application(jdbc, "DIDIMDOL", "EXPIRED", start.plusHours(1));
        history(jdbc, didimdol, "EXPIRED", start.plusHours(2));

        JsonNode rows = stats("2020-01-15");

        assertThat(rows).hasSize(3);
        assertThat(row(rows, "BOGEUMJARI")).containsExactly(2, 1, 1, 1, 0);
        assertThat(row(rows, "DIDIMDOL")).containsExactly(1, 0, 0, 0, 1);
        assertThat(row(rows, "NEWBORN_DIDIMDOL")).containsExactly(0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("[TC-BATCH-106] 같은 날짜 집계를 다시 실행(그 사이 승인 1건 추가) → 새 값으로 덮어씀, 중복 줄 없음")
    void dailyStats_rerunOverwrites() throws Exception {
        LocalDateTime noonKst = LocalDateTime.of(2020, 2, 10, 3, 0);
        long app = application(jdbc, "BOGEUMJARI", "APPROVED", noonKst);
        history(jdbc, app, "APPROVED", noonKst.plusMinutes(10));
        assertThat(row(stats("2020-02-10"), "BOGEUMJARI")).containsExactly(1, 1, 0, 0, 0);

        long another = application(jdbc, "BOGEUMJARI", "APPROVED", noonKst.plusHours(1));
        history(jdbc, another, "APPROVED", noonKst.plusHours(2));
        JsonNode rows = stats("2020-02-10");

        assertThat(rows).hasSize(3);
        assertThat(row(rows, "BOGEUMJARI")).containsExactly(2, 2, 0, 0, 0);
        assertThat(jdbc.queryForObject("select count(*) from daily_application_stats where stat_date = ?", Long.class,
                LocalDate.of(2020, 2, 10))).isEqualTo(3);
    }

    @Test
    @DisplayName("[TC-BATCH-107] 집계 조회 92일 200 / 93일·from > to·날짜 형식 오류 400, 집계 실행 날짜 형식 오류 400")
    void statsQuery_boundaries() throws Exception {
        api.adminGet(adminToken, "/api/admin/stats/daily?from=2020-01-01&to=2020-04-01").andExpect(status().isOk());
        api.adminGet(adminToken, "/api/admin/stats/daily?from=2020-01-01&to=2020-04-02")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("at most 92 days")));
        api.adminGet(adminToken, "/api/admin/stats/daily?from=2020-02-01&to=2020-01-31")
                .andExpect(status().isBadRequest());
        api.adminGet(adminToken, "/api/admin/stats/daily?from=2020-13-01&to=2020-12-31")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid parameter: from"));
        api.adminPost(adminToken, "/api/admin/batch/daily-stats?date=yesterday", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid parameter: date"));
    }

    @Test
    @DisplayName("[TC-BATCH-108] 서비스 토큰으로 배치 실행·집계 조회 → 403")
    void batchApi_adminOnly() throws Exception {
        mockMvc.perform(post("/api/admin/batch/expire").header("Authorization", bearer(serviceToken())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/stats/daily?from=2020-01-01&to=2020-01-02")
                .header("Authorization", bearer(serviceToken())))
                .andExpect(status().isForbidden());
    }
}
