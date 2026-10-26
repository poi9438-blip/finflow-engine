package com.finflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.domain.ProductCode;
import com.finflow.repository.ApplicationRepository;
import com.finflow.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.finflow.support.ApiTestSupport.applicantRef;
import static com.finflow.support.ApiTestSupport.applicationBody;
import static com.finflow.support.ApiTestSupport.bearer;
import static com.finflow.support.ApiTestSupport.serviceToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 신청 접수 멱등성 통합 테스트 (Idempotency-Key, 업무 명세 7장)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotencyApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;
    // 실제 저장소를 감싸서 동시 접수 테스트에서만 진행 중 확인을 멈춰 세움
    @SpyBean
    ApplicationRepository applicationRepository;

    private ApiTestSupport api;

    @BeforeEach
    void setUp() {
        api = new ApiTestSupport(mockMvc, objectMapper);
    }

    private long applications(String applicantRef) {
        return jdbc.queryForObject("select count(*) from applications where applicant_ref = ?", Long.class,
                applicantRef);
    }

    private long historyRows(String applicationId) {
        return jdbc.queryForObject("select count(*) from application_status_history h join applications a"
                + " on a.id = h.application_id where a.application_id = ?", Long.class, applicationId);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("[TC-IDEM-101] 같은 키 + 같은 본문을 두 번 → 201, 200(Idempotent-Replayed: true), 응답 본문이 같고 신청 1건·이력 1줄")
    void sameKeySameBody_replays() throws Exception {
        String ref = applicantRef();
        String key = key();
        MvcResult first = api.receive(applicationBody(ref), key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn();
        MvcResult second = api.receive(applicationBody(ref), key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        JsonNode a = api.read(first).get("data");
        JsonNode b = api.read(second).get("data");
        assertThat(b).isEqualTo(a);
        assertThat(applications(ref)).isEqualTo(1);
        assertThat(historyRows(a.get("applicationId").asText())).isEqualTo(1);
    }

    @Test
    @DisplayName("[TC-IDEM-102] 같은 키 + 같은 값, 표기만 다름(JSON 키 순서·공백·DTI 문자열 \"0.3041\" / 숫자 0.3041) → 200 재전송, 새 신청 없음")
    void sameValuesDifferentJson_replays() throws Exception {
        String ref = applicantRef();
        String key = key();
        api.receive(applicationBody(ref), key).andExpect(status().isCreated());

        String reordered = "{ \"simulationRef\" : 42, \"region\":\"부산\", \"childCount\":1,"
                + "  \"newbornWithin2Years\":false,\"newlywed\":false,\"firstHome\":true, \"dti\":0.3041,"
                + " \"householdAnnualIncome\":60000000, \"housePrice\":500000000, \"termMonths\":360,"
                + " \"requestedAmount\":300000000, \"applicantRef\":\"" + ref + "\", \"productCode\":\"BOGEUMJARI\" }";
        mockMvc.perform(post("/api/applications").header("Authorization", bearer(serviceToken()))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(reordered))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(applications(ref)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TC-IDEM-103] 같은 키 + 다른 본문(금액만 다름) → 409, 처음 신청 그대로·새 신청 없음")
    void sameKeyDifferentBody_conflict() throws Exception {
        String ref = applicantRef();
        String key = key();
        api.receive(applicationBody(ref), key).andExpect(status().isCreated());

        Map<String, Object> changed = applicationBody(ref);
        changed.put("requestedAmount", 250_000_000L);
        api.receive(changed, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Idempotency key reused with different request"));

        assertThat(applications(ref)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select requested_amount from applications where applicant_ref = ?",
                Long.class, ref)).isEqualTo(300_000_000L);
    }

    @Test
    @DisplayName("[TC-IDEM-104] 키 없음·7자·65자·허용 안 되는 문자 → 400 + 저장 없음, 8자·64자 → 201")
    void keyFormat_boundaries() throws Exception {
        String ref = applicantRef();
        api.receive(applicationBody(ref), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"));
        for (String bad : new String[] { "a".repeat(7), "a".repeat(65), "abc_defgh", "abc defgh" }) {
            api.receive(applicationBody(ref), bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.message").value("Idempotency-Key must be 8 to 64 letters, digits or hyphens"));
        }
        assertThat(applications(ref)).isZero();

        api.receive(applicationBody(applicantRef()), "k" + UUID.randomUUID().toString().substring(0, 7))
                .andExpect(status().isCreated());
        api.receive(applicationBody(applicantRef()), (UUID.randomUUID().toString() + "-").repeat(2).substring(0, 64))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("[TC-IDEM-105] 처음 신청이 취소된 뒤 같은 키 재전송 → 200 처음 응답 그대로(RECEIVED), 새 신청 없음")
    void replayAfterCancel_returnsOriginalReceipt() throws Exception {
        String ref = applicantRef();
        String key = key();
        JsonNode first = api.read(api.receive(applicationBody(ref), key).andReturn()).get("data");
        String applicationId = first.get("applicationId").asText();
        mockMvc.perform(post("/api/applications/" + applicationId + "/cancel")
                .header("Authorization", bearer(serviceToken())).contentType(MediaType.APPLICATION_JSON)
                .content(api.json(Map.of("applicantRef", ref))))
                .andExpect(status().isOk());

        JsonNode replay = api.read(api.receive(applicationBody(ref), key)
                .andExpect(status().isOk()).andReturn()).get("data");
        assertThat(replay).isEqualTo(first);
        assertThat(replay.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(applications(ref)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TC-IDEM-106] 진행 중 신청의 키로 재전송 → 200 재전송 (진행 중 중복 409보다 키 확인이 먼저), 다른 키면 409")
    void replayWinsOverActiveDuplicate() throws Exception {
        String ref = applicantRef();
        String key = key();
        api.receive(applicationBody(ref), key).andExpect(status().isCreated());

        api.receive(applicationBody(ref), key).andExpect(status().isOk());
        api.receive(applicationBody(ref), key())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Active application already exists"));
        assertThat(applications(ref)).isEqualTo(1);
    }

    // [학습] 그냥 동시에 보내면 두 요청이 차례로 처리되어 두 번째가 키 확인에서 걸릴 수 있음(2단계 #3).
    // 진행 중 확인에서 두 요청이 서로를 기다리게 해서, 둘 다 키 확인을 통과한 뒤 저장까지 가게 만듦
    // [추천 주석] 두 요청이 모두 키 확인을 통과한 뒤 저장 → 하나는 DB 제약에 막히고 키로 다시 조회해 재전송
    @Test
    @DisplayName("[TC-IDEM-107] 같은 키 + 같은 본문을 동시에(둘 다 키 확인 통과 후 저장) → 201 하나, 200 재전송 하나(같은 applicationId), 신청 1건")
    void concurrentSameKey_oneSavedOneReplayed() throws Exception {
        String ref = applicantRef();
        String key = key();
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothChecked.await(10, TimeUnit.SECONDS);
            return false;
        }).when(applicationRepository)
                .existsByApplicantRefAndProductCodeAndStatusIn(anyString(), any(ProductCode.class), anyCollection());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<MvcResult> results = new ArrayList<>();
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> api.receive(applicationBody(ref), key).andReturn()));
            }
            for (Future<MvcResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
        }

        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(201, 200);
        assertThat(results).extracting(r -> api.read(r).at("/data/applicationId").asText()).containsOnly(
                api.read(results.get(0)).at("/data/applicationId").asText());
        assertThat(applications(ref)).isEqualTo(1);
    }
}
