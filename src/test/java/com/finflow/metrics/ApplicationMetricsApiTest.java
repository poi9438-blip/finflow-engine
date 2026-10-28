package com.finflow.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.support.ApiTestSupport;
import com.finflow.support.FakeRiskWatch;
import com.finflow.support.FakeRiskWatch.Reply;
import com.finflow.support.TestMetrics;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static com.finflow.support.ApiTestSupport.applicantRef;
import static com.finflow.support.ApiTestSupport.applicationBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 업무 지표: 상태 변화는 저장된 것만, 심사 시작 때 RiskWatch 결과 (RiskWatch는 가짜 HTTP 서버)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestMetrics.class)
class ApplicationMetricsApiTest {

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
    InMemoryMetricReader metricReader;

    private ApiTestSupport api;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        RISKWATCH.reset();
        api = new ApiTestSupport(mockMvc, objectMapper);
        adminToken = api.operatorToken();
    }

    private long statusChanges(String toStatus) {
        return TestMetrics.count(metricReader, "finflow.application.status_changes",
                Attributes.of(ApplicationMetrics.PRODUCT_CODE, "BOGEUMJARI", ApplicationMetrics.TO_STATUS, toStatus));
    }

    private long riskWatch(String status) {
        return TestMetrics.count(metricReader, "finflow.riskwatch.evaluations",
                Attributes.of(ApplicationMetrics.STATUS, status));
    }

    @Test
    @DisplayName("[TC-OBS-101] 접수 → 같은 키 재전송 → 새 키(409) → 심사 시작 → 승인: RECEIVED 1(재전송·409는 세지 않음), REVIEWING 1, APPROVED 1")
    void countsSavedStatusChangesOnly() throws Exception {
        long received = statusChanges("RECEIVED");
        long reviewing = statusChanges("REVIEWING");
        long approved = statusChanges("APPROVED");
        Map<String, Object> body = applicationBody(applicantRef());
        String key = UUID.randomUUID().toString();

        String applicationId = api.read(api.receive(body, key).andExpect(status().isCreated()).andReturn())
                .at("/data/applicationId").asText();
        api.receive(body, key).andExpect(status().isOk());
        api.receive(body).andExpect(status().isConflict());
        assertThat(statusChanges("RECEIVED") - received).isEqualTo(1);

        RISKWATCH.reply(Reply.ok(FakeRiskWatch.evaluated(UUID.randomUUID().toString())));
        api.adminPost(adminToken, "/api/admin/applications/" + applicationId + "/review", null)
                .andExpect(status().isOk());
        api.adminPost(adminToken, "/api/admin/applications/" + applicationId + "/approve", null)
                .andExpect(status().isOk());
        assertThat(statusChanges("REVIEWING") - reviewing).isEqualTo(1);
        assertThat(statusChanges("APPROVED") - approved).isEqualTo(1);
    }

    @Test
    @DisplayName("[TC-OBS-102] 심사 시작: RiskWatch 판정이면 EVALUATED 1, 응답이 없으면 UNAVAILABLE 1, 두 지표의 다른 조합은 시작부터 0")
    void countsRiskWatchResults() throws Exception {
        long evaluated = riskWatch("EVALUATED");
        long unavailable = riskWatch("UNAVAILABLE");

        RISKWATCH.reply(Reply.ok(FakeRiskWatch.evaluated(UUID.randomUUID().toString())));
        api.adminPost(adminToken, "/api/admin/applications/" + api.receiveOk(applicationBody(applicantRef()))
                + "/review", null).andExpect(status().isOk());
        assertThat(riskWatch("EVALUATED") - evaluated).isEqualTo(1);
        assertThat(riskWatch("UNAVAILABLE") - unavailable).isZero();

        // RiskWatchClient는 최대 2번 시도
        RISKWATCH.reply(Reply.error(503), Reply.error(503));
        api.adminPost(adminToken, "/api/admin/applications/" + api.receiveOk(applicationBody(applicantRef()))
                + "/review", null).andExpect(status().isOk());
        assertThat(riskWatch("UNAVAILABLE") - unavailable).isEqualTo(1);
        assertThat(riskWatch("EVALUATED") - evaluated).isEqualTo(1);
        // 기록하지 않은 조합도 0으로 있음 (Prometheus increase()가 첫 건을 잡게): 결과 3가지, 상품 3 × 상태 6
        assertThat(TestMetrics.series(metricReader, "finflow.riskwatch.evaluations")).isEqualTo(3);
        assertThat(TestMetrics.series(metricReader, "finflow.application.status_changes")).isEqualTo(3 * 6);
    }
}
