package com.finflow.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.integration.RiskWatchClient;
import com.finflow.security.AuthPrincipal;
import com.finflow.service.ApplicationService;
import com.finflow.support.ApiTestSupport;
import com.finflow.support.FakeRiskWatch;
import com.finflow.support.FakeRiskWatch.Reply;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.finflow.support.ApiTestSupport.applicantRef;
import static com.finflow.support.ApiTestSupport.applicationBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 심사 시작 때 RiskWatch 판정 저장 통합 테스트 (RiskWatch는 가짜 HTTP 서버)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewRiskApiTest {

    // application-test.yml의 테스트 전용 값
    static final String RISKWATCH_SECRET = "FinFlow-Test-RiskWatch-Token-Secret-32bytes";
    static final FakeRiskWatch RISKWATCH = FakeRiskWatch.start();

    @DynamicPropertySource
    static void riskWatchUrl(DynamicPropertyRegistry registry) {
        registry.add("app.riskwatch.base-url", RISKWATCH::url);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApplicationService applicationService;
    // 실제 클라이언트를 감싸서 호출 시점의 트랜잭션 상태·호출 여부만 확인
    @SpyBean
    RiskWatchClient riskWatchClient;

    private ApiTestSupport api;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        RISKWATCH.reset();
        api = new ApiTestSupport(mockMvc, objectMapper);
        adminToken = api.operatorToken();
    }

    private String received(String applicantRef) throws Exception {
        return api.receiveOk(applicationBody(applicantRef));
    }

    private MvcResult review(String applicationId, String traceId) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/admin/applications/" + applicationId + "/review")
                .header("Authorization", ApiTestSupport.bearer(adminToken));
        if (traceId != null) {
            request = request.header("X-Trace-Id", traceId);
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode reviewOk(String applicationId) throws Exception {
        MvcResult result = review(applicationId, null);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return api.read(result).get("data");
    }

    private Map<String, Object> riskColumns(String applicationId) {
        return jdbc.queryForMap("select status, risk_status, risk_evaluation_id, risk_verdict, risk_criteria_version,"
                + " risk_evaluated_at, risk_failure, version from applications where application_id = ?", applicationId);
    }

    @Test
    @DisplayName("[TC-RISK-101] 심사 시작 → RiskWatch 1번 호출(신청 값 + sourceRef=신청 번호, X-Trace-Id), 200 REVIEWING, risk EVALUATED 저장, 상세에도 같은 값")
    void startReview_savesEvaluation() throws Exception {
        String ref = applicantRef();
        String applicationId = received(ref);
        String evaluationId = UUID.randomUUID().toString();
        RISKWATCH.reply(Reply.ok(FakeRiskWatch.evaluated(evaluationId)));

        MvcResult result = review(applicationId, "hfm-risk-101");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = api.read(result).get("data");
        assertThat(data.get("status").asText()).isEqualTo("REVIEWING");
        JsonNode risk = data.get("risk");
        assertThat(risk.get("status").asText()).isEqualTo("EVALUATED");
        assertThat(risk.get("evaluationId").asText()).isEqualTo(evaluationId);
        assertThat(risk.get("verdict").asText()).isEqualTo("REVIEW");
        assertThat(risk.get("criteriaVersion").asText()).isEqualTo("2026-10-26");
        // RiskWatch 시각(소수 7자리)은 마이크로초로 잘라 UTC로 저장
        assertThat(risk.get("evaluatedAt").asText()).isEqualTo("2026-10-27T01:02:03.123456Z");
        assertThat(risk.get("failure").isNull()).isTrue();
        // 상세 조회에도 같은 값, 판정과 상태가 한 번에 저장됨 (버전 한 번만 오름)
        JsonNode detail = api.read(api.adminGet(adminToken, "/api/admin/applications/" + applicationId).andReturn())
                .get("data");
        assertThat(detail.get("risk")).isEqualTo(risk);
        assertThat(riskColumns(applicationId)).containsEntry("VERSION", 1L).containsEntry("RISK_STATUS", "EVALUATED");

        List<FakeRiskWatch.Received> calls = RISKWATCH.received();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).headers()).containsEntry("x-trace-id", "hfm-risk-101");
        Map<String, Object> sent = objectMapper.readValue(calls.get(0).body(), LinkedHashMap.class);
        Map<String, Object> expected = new LinkedHashMap<>(applicationBody(ref));
        expected.remove("simulationRef");
        expected.put("sourceRef", applicationId);
        expected.put("dti", 0.3041);
        assertThat(sent).containsOnlyKeys(expected.keySet());
        expected.forEach((k, v) -> assertThat(String.valueOf(sent.get(k))).as(k).isEqualTo(String.valueOf(v)));
    }

    @Test
    @DisplayName("[TC-RISK-102] RiskWatch로 보낸 서비스 토큰: RISKWATCH_TOKEN_SECRET 서명, iss=finflow, aud=riskwatch, 유효 기간 5분 이하")
    void serviceTokenToRiskWatch() throws Exception {
        reviewOk(received(applicantRef()));

        String authorization = RISKWATCH.received().get(0).headers().get("authorization");
        assertThat(authorization).startsWith("Bearer ");
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(RISKWATCH_SECRET.getBytes(StandardCharsets.UTF_8)))
                .requireIssuer("finflow")
                .requireAudience("riskwatch")
                .build()
                .parseSignedClaims(authorization.substring(7))
                .getPayload();
        long lifetime = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(lifetime).isPositive().isLessThanOrEqualTo(5 * 60 * 1000L);
    }

    @Test
    @DisplayName("[TC-RISK-103] RiskWatch 연결 끊김 / 500 / 시간 초과 / 401 / 400 / 모양이 다른 응답 → 심사는 시작(200), risk UNAVAILABLE + 짧은 이유, 판정 칸 비어 있음")
    void riskWatchFailures_reviewStillStarts() throws Exception {
        // 다른 칸은 맞고 판정만 없는 값 (판정 값 확인이 따로 동작하는지)
        String unknownVerdict = FakeRiskWatch.evaluated(UUID.randomUUID().toString())
                .replace("\"verdict\":\"REVIEW\"", "\"verdict\":\"OK\"");
        List<Map.Entry<String, Reply[]>> cases = List.of(
                Map.entry("connection", new Reply[] { Reply.disconnect(), Reply.disconnect() }),
                Map.entry("HTTP 500", new Reply[] { Reply.error(500), Reply.error(500) }),
                // 응답 시간 3초 설정 대신 테스트는 0.5초 (application-test.yml)
                Map.entry("timeout", new Reply[] { Reply.slow(1500) }),
                Map.entry("HTTP 401", new Reply[] { Reply.error(401) }),
                Map.entry("HTTP 400", new Reply[] { Reply.error(400) }),
                Map.entry("invalid response", new Reply[] {
                        Reply.ok("{\"status\":\"SUCCESS\",\"data\":{\"verdict\":\"REVIEW\"}}") }),
                Map.entry("invalid response", new Reply[] { Reply.ok(unknownVerdict) }));

        for (Map.Entry<String, Reply[]> c : cases) {
            RISKWATCH.reset();
            RISKWATCH.reply(c.getValue());
            String applicationId = received(applicantRef());
            JsonNode data = reviewOk(applicationId);

            assertThat(data.get("status").asText()).as(c.getKey()).isEqualTo("REVIEWING");
            assertThat(data.at("/risk/status").asText()).as(c.getKey()).isEqualTo("UNAVAILABLE");
            assertThat(data.at("/risk/failure").asText()).as(c.getKey()).isEqualTo(c.getKey());
            assertThat(riskColumns(applicationId)).as(c.getKey()).containsEntry("RISK_EVALUATION_ID", null)
                    .containsEntry("RISK_VERDICT", null).containsEntry("RISK_CRITERIA_VERSION", null);
        }
    }

    @Test
    @DisplayName("[TC-RISK-103] RiskWatch가 떠 있지 않음(연결 거부) → UNAVAILABLE connection")
    void riskWatchRefused_connection() {
        RiskWatchClient refused = new RiskWatchClient(new org.springframework.boot.web.client.RestTemplateBuilder(),
                new com.finflow.integration.RiskWatchTokens(RISKWATCH_SECRET, ApiTestSupport.SERVICE_SECRET,
                        ApiTestSupport.OPERATOR_SECRET),
                "http://127.0.0.1:9", 500, 500, 0);
        var request = new com.finflow.integration.RiskEvaluationRequest("DIDIMDOL", "homefin:1", "app-1", 1, 360, 1, 0,
                java.math.BigDecimal.ZERO, "NONE", false, false, false, false, 0, null);
        var result = refused.evaluate(request, "hfm-refused");
        assertThat(result.status().name()).isEqualTo("UNAVAILABLE");
        assertThat(result.failure()).isEqualTo("connection");
    }

    @Test
    @DisplayName("[TC-RISK-104] 재시도: 연결 끊김·5xx는 한 번 더(총 2번, 두 번째에 성공하면 판정 저장), 시간 초과·4xx는 1번만")
    void retryOnlyConnectionAndServerErrors() throws Exception {
        Map<String, List<Reply>> cases = new LinkedHashMap<>();
        cases.put("2:connection", List.of(Reply.disconnect(), Reply.disconnect()));
        cases.put("2:HTTP 503", List.of(Reply.error(503), Reply.error(503)));
        cases.put("2:EVALUATED", List.of(Reply.error(500)));
        cases.put("1:timeout", List.of(Reply.slow(1500), Reply.error(500)));
        cases.put("1:HTTP 401", List.of(Reply.error(401)));
        cases.put("1:HTTP 409", List.of(Reply.error(409)));

        List<String> results = new ArrayList<>();
        for (Map.Entry<String, List<Reply>> c : cases.entrySet()) {
            RISKWATCH.reset();
            RISKWATCH.reply(c.getValue().toArray(Reply[]::new));
            JsonNode risk = reviewOk(received(applicantRef())).get("risk");
            String outcome = risk.get("status").asText().equals("EVALUATED") ? "EVALUATED"
                    : risk.get("failure").asText();
            results.add(RISKWATCH.received().size() + ":" + outcome);
        }
        assertThat(results).containsExactlyElementsOf(cases.keySet());
    }

    @Test
    @DisplayName("[TC-RISK-105] 5단계 전에 접수된 신청(주택 보유·맞벌이 비어 있음) → RiskWatch를 부르지 않고 심사 시작, risk INPUT_MISSING")
    void legacyApplication_inputMissing() throws Exception {
        String applicationId = received(applicantRef());
        jdbc.update("update applications set house_ownership = null, dual_income = null where application_id = ?",
                applicationId);

        JsonNode data = reviewOk(applicationId);
        assertThat(data.get("status").asText()).isEqualTo("REVIEWING");
        assertThat(data.at("/risk/status").asText()).isEqualTo("INPUT_MISSING");
        assertThat(data.get("houseOwnership").isNull()).isTrue();
        assertThat(RISKWATCH.received()).isEmpty();
        verify(riskWatchClient, never()).evaluate(any(), any());
    }

    @Test
    @DisplayName("[TC-RISK-106] 이미 심사 중 / 취소된 신청의 심사 시작 → 409, RiskWatch를 부르지 않음")
    void cannotStart_noRiskWatchCall() throws Exception {
        String reviewing = received(applicantRef());
        reviewOk(reviewing);
        String ref = applicantRef();
        String cancelled = received(ref);
        applicationService.cancel(cancelled, ref, AuthPrincipal.service());
        RISKWATCH.reset();

        for (String applicationId : List.of(reviewing, cancelled)) {
            MvcResult result = review(applicationId, null);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(api.read(result).get("message").asText()).startsWith("Invalid status transition: ");
        }
        assertThat(RISKWATCH.received()).isEmpty();
    }

    @Test
    @DisplayName("[TC-RISK-107] RiskWatch를 부르는 동안 열린 DB 트랜잭션이 없음 (응답을 기다리며 DB 연결을 잡지 않음)")
    void noTransactionDuringRiskWatchCall() throws Exception {
        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        AtomicReference<Boolean> synchronizationActive = new AtomicReference<>();
        doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            synchronizationActive.set(TransactionSynchronizationManager.isSynchronizationActive());
            return invocation.callRealMethod();
        }).when(riskWatchClient).evaluate(any(), any());

        reviewOk(received(applicantRef()));
        assertThat(transactionActive.get()).isFalse();
        assertThat(synchronizationActive.get()).isFalse();
    }

    @Test
    @DisplayName("[TC-RISK-108] RiskWatch 응답을 기다리는 사이 신청자가 취소 → 심사 시작 409, 신청은 CANCELLED 그대로, 판정 저장 안 함")
    void cancelledWhileWaiting_conflict() throws Exception {
        String ref = applicantRef();
        String applicationId = received(ref);
        // 가짜 RiskWatch가 요청을 받은 순간(응답 전) 다른 스레드에서 취소
        RISKWATCH.onRequest(() -> applicationService.cancel(applicationId, ref, AuthPrincipal.service()));

        MvcResult result = review(applicationId, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(RISKWATCH.received()).hasSize(1);
        assertThat(riskColumns(applicationId)).containsEntry("STATUS", "CANCELLED").containsEntry("RISK_STATUS", null);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/admin/applications/" + applicationId)
                .header("Authorization", ApiTestSupport.bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.risk").doesNotExist());
    }
}
