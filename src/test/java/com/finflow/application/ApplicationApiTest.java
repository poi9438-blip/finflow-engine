package com.finflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.support.ApiTestSupport;
import com.finflow.util.EncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.finflow.support.ApiTestSupport.applicantRef;
import static com.finflow.support.ApiTestSupport.applicationBody;
import static com.finflow.support.ApiTestSupport.bearer;
import static com.finflow.support.ApiTestSupport.serviceToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 신청 접수·조회·취소(서비스), 심사·승인·거절·목록·상세(운영자), 상태 이력 통합 테스트
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationApiTest {

        private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

        @Autowired
        MockMvc mockMvc;
        @Autowired
        ObjectMapper objectMapper;
        @Autowired
        JdbcTemplate jdbc;
        @Autowired
        EncryptionUtil encryptionUtil;

        private ApiTestSupport api;
        private String adminToken;

        @BeforeEach
        void setUp() throws Exception {
                api = new ApiTestSupport(mockMvc, objectMapper);
                adminToken = api.operatorToken();
        }

        // DB id로 이력 줄 조회 (시간순)
        private List<Map<String, Object>> history(String applicationId) {
                return jdbc.queryForList(
                                "select h.from_status, h.to_status, h.actor_type, h.actor_id, h.reason, h.trace_id"
                                                + " from application_status_history h join applications a on a.id = h.application_id"
                                                + " where a.application_id = ? order by h.created_at, h.id",
                                applicationId);
        }

        private String dbStatus(String applicationId) {
                return jdbc.queryForObject("select status from applications where application_id = ?", String.class,
                                applicationId);
        }

        private String admin(String applicationId, String action) {
                return "/api/admin/applications/" + applicationId + "/" + action;
        }

        private MvcResult cancel(String applicationId, String applicantRef) throws Exception {
                return mockMvc.perform(post("/api/applications/" + applicationId + "/cancel")
                                .header("Authorization", bearer(serviceToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(api.json(Map.of("applicantRef", applicantRef)))).andReturn();
        }

        @Test
        @DisplayName("[TC-APP-101] 신청 접수 → 201 {applicationId(UUID), RECEIVED, receivedAt(…Z)}, DB 순번 id 없음, 접수 이력 1줄(SERVICE, homefin-mate, 받은 traceId)")
        void receive_createsApplicationAndHistory() throws Exception {
                String ref = applicantRef();
                MvcResult result = mockMvc.perform(post("/api/applications")
                                .header("Authorization", bearer(serviceToken()))
                                .header("X-Trace-Id", "hfm-receive-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(api.json(applicationBody(ref))))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.data.applicationId", matchesPattern(UUID_PATTERN)))
                                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                                .andExpect(jsonPath("$.data.receivedAt", matchesPattern(".*Z$")))
                                .andExpect(jsonPath("$.data.id").doesNotExist())
                                .andReturn();
                String applicationId = api.read(result).at("/data/applicationId").asText();

                List<Map<String, Object>> rows = history(applicationId);
                assertThat(rows).hasSize(1);
                assertThat(rows.get(0)).containsEntry("FROM_STATUS", null).containsEntry("TO_STATUS", "RECEIVED")
                                .containsEntry("ACTOR_TYPE", "SERVICE").containsEntry("ACTOR_ID", "homefin-mate")
                                .containsEntry("TRACE_ID", "hfm-receive-1");
        }

        @Test
        @DisplayName("[TC-APP-102] 범위 밖 입력(금액 0·10억 1원, 기간 11·601, DTI 10, 자녀 21, applicantRef 형식, 없는 상품, 필수값 없음) → 400 + 필드 이름, 저장 없음")
        void receive_outOfRange_rejected() throws Exception {
                String ref = applicantRef();
                Object[][] cases = {
                                { "requestedAmount", 0 }, { "requestedAmount", 1_000_000_001L },
                                { "termMonths", 11 }, { "termMonths", 601 },
                                { "dti", "10" }, { "childCount", 21 },
                                { "applicantRef", "user@test.com" }, { "productCode", "GUARANTEE" },
                                { "housePrice", null }, { "firstHome", null },
                };
                for (Object[] c : cases) {
                        Map<String, Object> body = applicationBody(ref);
                        body.put((String) c[0], c[1]);
                        api.receive(body)
                                        .andExpect(status().isBadRequest())
                                        .andExpect(jsonPath("$.message", containsString((String) c[0])));
                }
                assertThat(jdbc.queryForObject("select count(*) from applications where applicant_ref = ?", Long.class,
                                ref))
                                .isZero();
        }

        @Test
        @DisplayName("[TC-APP-103] 범위 양 끝 입력(금액 1·10억, 기간 12·600, DTI 0·9.9999, 자녀 0·20) → 모두 201")
        void receive_boundaryValues_accepted() throws Exception {
                Object[][] cases = {
                                { "requestedAmount", 1 }, { "requestedAmount", 1_000_000_000L },
                                { "termMonths", 12 }, { "termMonths", 600 },
                                { "dti", "0" }, { "dti", "9.9999" },
                                { "childCount", 0 }, { "childCount", 20 },
                };
                for (Object[] c : cases) {
                        // 진행 중 중복 규칙에 걸리지 않게 신청자를 매번 새로
                        Map<String, Object> body = applicationBody(applicantRef());
                        body.put((String) c[0], c[1]);
                        api.receive(body).andExpect(status().isCreated());
                }
        }

        @Test
        @DisplayName("[TC-APP-104] 같은 신청자·상품의 진행 중 신청이 있으면 409, 다른 상품은 201, 앞 신청이 취소된 뒤에는 201")
        void receive_activeDuplicate_conflict() throws Exception {
                String ref = applicantRef();
                String first = api.receiveOk(applicationBody(ref));

                api.receive(applicationBody(ref))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.message").value("Active application already exists"));

                Map<String, Object> otherProduct = applicationBody(ref);
                otherProduct.put("productCode", "DIDIMDOL");
                api.receive(otherProduct).andExpect(status().isCreated());

                assertThat(cancel(first, ref).getResponse().getStatus()).isEqualTo(200);
                api.receive(applicationBody(ref)).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("[TC-APP-105] DB의 연소득·DTI 칸은 암호문 (요청 값이 그대로 없음), 복호화하면 요청 값")
        void receive_encryptsIncomeAndDti() throws Exception {
                String applicationId = api.receiveOk(applicationBody(applicantRef()));

                Map<String, Object> row = jdbc.queryForMap(
                                "select household_annual_income, dti from applications where application_id = ?",
                                applicationId);
                String income = (String) row.get("HOUSEHOLD_ANNUAL_INCOME");
                String dti = (String) row.get("DTI");
                assertThat(income).doesNotContain("60000000");
                assertThat(dti).doesNotContain("0.3041");
                assertThat(encryptionUtil.decrypt(income)).isEqualTo("60000000");
                assertThat(encryptionUtil.decrypt(dti)).isEqualTo("0.3041");
        }

        @Test
        @DisplayName("[TC-APP-106] 신청자 조회: applicantRef 같음 200(소득·DTI 없음) / 다름 404 / 없는 번호 404")
        void applicantView_onlyForOwner() throws Exception {
                String ref = applicantRef();
                String applicationId = api.receiveOk(applicationBody(ref));

                mockMvc.perform(get("/api/applications/" + applicationId).param("applicantRef", ref)
                                .header("Authorization", bearer(serviceToken())))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                                .andExpect(jsonPath("$.data.requestedAmount").value(300000000))
                                .andExpect(jsonPath("$.data.householdAnnualIncome").doesNotExist())
                                .andExpect(jsonPath("$.data.dti").doesNotExist());
                mockMvc.perform(get("/api/applications/" + applicationId).param("applicantRef", applicantRef())
                                .header("Authorization", bearer(serviceToken())))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.message").value("Application not found"));
                mockMvc.perform(get("/api/applications/" + UUID.randomUUID()).param("applicantRef", ref)
                                .header("Authorization", bearer(serviceToken())))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("[TC-APP-107] 접수 → 심사 시작 → 승인: 최종 APPROVED·decidedAt, 이력 3줄(SERVICE → ADMIN → ADMIN, 운영자 id·각 요청의 traceId)")
        void reviewAndApprove_recordsHistory() throws Exception {
                String applicationId = api.receiveOk(applicationBody(applicantRef()));

                mockMvc.perform(post(admin(applicationId, "review")).header("Authorization", bearer(adminToken))
                                .header("X-Trace-Id", "ff-review-1"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.status").value("REVIEWING"));
                mockMvc.perform(post(admin(applicationId, "approve")).header("Authorization", bearer(adminToken))
                                .header("X-Trace-Id", "ff-approve-1")
                                .contentType(MediaType.APPLICATION_JSON).content(api.json(Map.of("note", "서류 확인 완료"))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                                .andExpect(jsonPath("$.data.decidedAt", matchesPattern(".*Z$")))
                                .andExpect(jsonPath("$.data.decisionNote").value("서류 확인 완료"));

                List<Map<String, Object>> rows = history(applicationId);
                assertThat(rows).extracting(r -> r.get("TO_STATUS")).containsExactly("RECEIVED", "REVIEWING",
                                "APPROVED");
                assertThat(rows).extracting(r -> r.get("ACTOR_TYPE")).containsExactly("SERVICE", "ADMIN", "ADMIN");
                assertThat((String) rows.get(1).get("ACTOR_ID")).matches("operator:\\d+");
                assertThat(rows).extracting(r -> r.get("TRACE_ID")).element(1).isEqualTo("ff-review-1");
                assertThat(rows).extracting(r -> r.get("TRACE_ID")).element(2).isEqualTo("ff-approve-1");
                assertThat(rows.get(2).get("REASON")).isEqualTo("서류 확인 완료");
        }

        @Test
        @DisplayName("[TC-APP-108] 거절 사유 없음 400 / 501자 400 / 500자 200 REJECTED, 사유가 신청자 조회에 보임")
        void reject_reasonBoundary() throws Exception {
                String ref = applicantRef();
                String applicationId = api.receiveOk(applicationBody(ref));
                api.adminPost(adminToken, admin(applicationId, "review"), null).andExpect(status().isOk());

                api.adminPost(adminToken, admin(applicationId, "reject"), Map.of())
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message", containsString("reason")));
                api.adminPost(adminToken, admin(applicationId, "reject"), Map.of("reason", "가".repeat(501)))
                                .andExpect(status().isBadRequest());
                String reason = "가".repeat(500);
                api.adminPost(adminToken, admin(applicationId, "reject"), Map.of("reason", reason))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.status").value("REJECTED"));

                mockMvc.perform(get("/api/applications/" + applicationId).param("applicantRef", ref)
                                .header("Authorization", bearer(serviceToken())))
                                .andExpect(jsonPath("$.data.rejectReason").value(reason))
                                .andExpect(jsonPath("$.data.decidedAt").exists());
        }

        @Test
        @DisplayName("[TC-APP-109] 취소: 접수 상태 200 / 심사 중 200 / 이미 취소됨 200(이력 추가 없음) / applicantRef 다름 404")
        void cancel_rules() throws Exception {
                String ref = applicantRef();
                String received = api.receiveOk(applicationBody(ref));
                assertThat(api.read(cancel(received, ref)).at("/data/status").asText()).isEqualTo("CANCELLED");

                Map<String, Object> other = applicationBody(ref);
                other.put("productCode", "DIDIMDOL");
                String reviewing = api.receiveOk(other);
                api.adminPost(adminToken, admin(reviewing, "review"), null).andExpect(status().isOk());
                assertThat(api.read(cancel(reviewing, ref)).at("/data/status").asText()).isEqualTo("CANCELLED");

                int rowsBefore = history(received).size();
                MvcResult again = cancel(received, ref);
                assertThat(again.getResponse().getStatus()).isEqualTo(200);
                assertThat(api.read(again).at("/data/status").asText()).isEqualTo("CANCELLED");
                assertThat(history(received)).hasSize(rowsBefore);

                assertThat(cancel(received, applicantRef()).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("[TC-APP-110] 표에 없는 전이(접수→승인, 승인→거절, 거절→심사, 승인→취소, 만료→심사) → 409, 상태·이력 변화 없음")
        void invalidTransitions_conflict() throws Exception {
                String ref = applicantRef();
                String a = api.receiveOk(applicationBody(ref));
                api.adminPost(adminToken, admin(a, "approve"), null)
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.message")
                                                .value("Invalid status transition: RECEIVED -> APPROVED"));

                api.adminPost(adminToken, admin(a, "review"), null).andExpect(status().isOk());
                api.adminPost(adminToken, admin(a, "approve"), null).andExpect(status().isOk());
                int approvedRows = history(a).size();
                api.adminPost(adminToken, admin(a, "reject"), Map.of("reason", "늦은 거절"))
                                .andExpect(status().isConflict());
                assertThat(cancel(a, ref).getResponse().getStatus()).isEqualTo(409);
                assertThat(dbStatus(a)).isEqualTo("APPROVED");
                assertThat(history(a)).hasSize(approvedRows);

                Map<String, Object> second = applicationBody(ref);
                second.put("productCode", "DIDIMDOL");
                String b = api.receiveOk(second);
                api.adminPost(adminToken, admin(b, "review"), null).andExpect(status().isOk());
                api.adminPost(adminToken, admin(b, "reject"), Map.of("reason", "소득 기준 초과")).andExpect(status().isOk());
                api.adminPost(adminToken, admin(b, "review"), null).andExpect(status().isConflict());
                assertThat(dbStatus(b)).isEqualTo("REJECTED");

                // 만료는 4단계 배치가 만드는 상태라 DB 값을 직접 바꿈
                Map<String, Object> third = applicationBody(ref);
                third.put("productCode", "NEWBORN_DIDIMDOL");
                String c = api.receiveOk(third);
                jdbc.update("update applications set status = 'EXPIRED' where application_id = ?", c);
                api.adminPost(adminToken, admin(c, "review"), null)
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.message")
                                                .value("Invalid status transition: EXPIRED -> REVIEWING"));
                assertThat(history(c)).hasSize(1);
        }

        @Test
        @DisplayName("[TC-APP-111] 심사 중인 같은 신청에 승인과 거절을 동시에 → 하나만 200, 나머지 409, 결정 이력은 1줄")
        void concurrentDecisions_onlyOneWins() throws Exception {
                String applicationId = api.receiveOk(applicationBody(applicantRef()));
                api.adminPost(adminToken, admin(applicationId, "review"), null).andExpect(status().isOk());

                List<Integer> statuses = runTogether(
                                () -> api.adminPost(adminToken, admin(applicationId, "approve"), null).andReturn()
                                                .getResponse().getStatus(),
                                () -> api.adminPost(adminToken, admin(applicationId, "reject"),
                                                Map.of("reason", "동시 거절"))
                                                .andReturn().getResponse().getStatus());

                assertThat(statuses).containsExactlyInAnyOrder(200, 409);
                List<Map<String, Object>> decisions = history(applicationId).stream()
                                .filter(r -> List.of("APPROVED", "REJECTED").contains(r.get("TO_STATUS"))).toList();
                assertThat(decisions).hasSize(1);
                assertThat(dbStatus(applicationId)).isEqualTo(decisions.get(0).get("TO_STATUS"));
        }

        @Test
        @DisplayName("[TC-APP-112] 운영자 목록: 상태·상품 필터, 최신순, 페이지끼리 겹치지 않음, size 100은 200 / 101은 400, 소득·DTI 없음")
        void adminList_filtersAndPages() throws Exception {
                String ref = applicantRef();
                String older = api.receiveOk(applicationBody(ref));
                Map<String, Object> body = applicationBody(ref);
                body.put("productCode", "NEWBORN_DIDIMDOL");
                String newer = api.receiveOk(body);

                JsonNode all = api.read(api.adminGet(adminToken, "/api/admin/applications?size=100")
                                .andExpect(status().isOk()).andReturn()).get("data");
                List<String> ids = new ArrayList<>();
                List<OffsetDateTime> times = new ArrayList<>();
                all.forEach(n -> {
                        ids.add(n.get("applicationId").asText());
                        times.add(OffsetDateTime.parse(n.get("receivedAt").asText()));
                        assertThat(n.has("householdAnnualIncome")).isFalse();
                        assertThat(n.has("dti")).isFalse();
                });
                assertThat(ids.indexOf(newer)).isLessThan(ids.indexOf(older));
                assertThat(times).isSortedAccordingTo((x, y) -> y.compareTo(x));

                JsonNode filtered = api.read(api.adminGet(adminToken,
                                "/api/admin/applications?status=RECEIVED&productCode=NEWBORN_DIDIMDOL&size=100")
                                .andReturn())
                                .get("data");
                assertThat(filtered).isNotEmpty();
                filtered.forEach(n -> {
                        assertThat(n.get("status").asText()).isEqualTo("RECEIVED");
                        assertThat(n.get("productCode").asText()).isEqualTo("NEWBORN_DIDIMDOL");
                });

                List<String> page0 = new ArrayList<>();
                List<String> page1 = new ArrayList<>();
                api.read(api.adminGet(adminToken, "/api/admin/applications?page=0&size=1").andReturn()).get("data")
                                .forEach(n -> page0.add(n.get("applicationId").asText()));
                api.read(api.adminGet(adminToken, "/api/admin/applications?page=1&size=1").andReturn()).get("data")
                                .forEach(n -> page1.add(n.get("applicationId").asText()));
                assertThat(page0).hasSize(1).doesNotContainAnyElementsOf(page1);

                api.adminGet(adminToken, "/api/admin/applications?size=101")
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message", containsString("size must be between 1 and 100")));
                api.adminGet(adminToken, "/api/admin/applications?status=DONE")
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value("Invalid parameter: status"));
        }

        @Test
        @DisplayName("[TC-APP-113] 운영자 상세: 입력값 전체(복호화한 연소득·DTI 포함) + 상태 이력(시간순)")
        void adminDetail_showsAllFieldsAndHistory() throws Exception {
                String ref = applicantRef();
                String applicationId = api.receiveOk(applicationBody(ref));
                api.adminPost(adminToken, admin(applicationId, "review"), null).andExpect(status().isOk());

                api.adminGet(adminToken, "/api/admin/applications/" + applicationId)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.applicantRef").value(ref))
                                .andExpect(jsonPath("$.data.type").value("LOAN"))
                                .andExpect(jsonPath("$.data.householdAnnualIncome").value(60000000))
                                .andExpect(jsonPath("$.data.dti").value(0.3041))
                                .andExpect(jsonPath("$.data.housePrice").value(500000000))
                                .andExpect(jsonPath("$.data.firstHome").value(true))
                                .andExpect(jsonPath("$.data.childCount").value(1))
                                .andExpect(jsonPath("$.data.simulationRef").value(42))
                                .andExpect(jsonPath("$.data.history[0].toStatus").value("RECEIVED"))
                                .andExpect(jsonPath("$.data.history[1].fromStatus").value("RECEIVED"))
                                .andExpect(jsonPath("$.data.history[1].toStatus").value("REVIEWING"));
                api.adminGet(adminToken, "/api/admin/applications/" + UUID.randomUUID())
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("[TC-APP-114] 같은 신청자·상품 신청이 동시에 두 번 → 하나만 201, 나머지 409, 진행 중 신청은 1건 (DB unique 제약)")
        void concurrentDuplicateReceive_onlyOneSaved() throws Exception {
                String ref = applicantRef();
                Callable<Integer> receive = () -> api.receive(applicationBody(ref)).andReturn().getResponse()
                                .getStatus();

                assertThat(runTogether(receive, receive)).containsExactlyInAnyOrder(201, 409);
                assertThat(jdbc.queryForObject("select count(*) from applications where applicant_ref = ?"
                                + " and status in ('RECEIVED', 'REVIEWING')", Long.class, ref)).isEqualTo(1);
        }

        // 두 요청을 최대한 같은 순간에 시작해서 결과(상태 코드)를 모음
        @SafeVarargs
        private List<Integer> runTogether(Callable<Integer>... tasks) throws Exception {
                ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
                CountDownLatch start = new CountDownLatch(1);
                try {
                        List<Future<Integer>> futures = new ArrayList<>();
                        for (Callable<Integer> task : tasks) {
                                futures.add(pool.submit(() -> {
                                        start.await();
                                        return task.call();
                                }));
                        }
                        start.countDown();
                        List<Integer> results = new ArrayList<>();
                        for (Future<Integer> f : futures) {
                                results.add(f.get());
                        }
                        return results;
                } finally {
                        pool.shutdown();
                }
        }
}
