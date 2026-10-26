package com.finflow.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.entity.Operator;
import com.finflow.repository.OperatorRepository;
import com.finflow.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.finflow.support.ApiTestSupport.applicantRef;
import static com.finflow.support.ApiTestSupport.applicationBody;
import static com.finflow.support.ApiTestSupport.bearer;
import static com.finflow.support.ApiTestSupport.serviceToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 운영자 로그인, 서비스·운영자 토큰, 역할 구분 통합 테스트 (보안 필터 포함)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    OperatorRepository operatorRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    OperatorTokens operatorTokens;
    @Autowired
    JdbcTemplate jdbc;

    private ApiTestSupport api;

    @BeforeEach
    void setUp() {
        api = new ApiTestSupport(mockMvc, objectMapper);
    }

    // 테스트마다 새 운영자 (로그인 실패 횟수가 다른 테스트와 섞이지 않게)
    private String newOperator(String password) {
        String email = "op-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        operatorRepository.save(Operator.builder().email(email).passwordHash(passwordEncoder.encode(password))
                .createdAt(LocalDateTime.now()).build());
        return email;
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(api.json(Map.of("email", email, "password", password)))).andReturn();
    }

    private int receiveWith(String token) throws Exception {
        return mockMvc
                .perform(post("/api/applications").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(api.json(applicationBody(applicantRef()))))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("[TC-AUTH-101] 운영자 로그인 성공 → 200 {token, expiresAt}, 토큰으로 운영자 API 사용 가능, 응답에 비밀번호·해시 없음")
    void login_success() throws Exception {
        String email = newOperator("Right-Pass!1");
        MvcResult result = login(email, "Right-Pass!1");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = api.read(result).get("data");
        assertThat(data.get("token").asText()).isNotBlank();
        assertThat(Instant.parse(data.get("expiresAt").asText())).isAfter(Instant.now());
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("password").doesNotContain("$2a$");

        api.adminGet(data.get("token").asText(), "/api/admin/applications").andExpect(status().isOk());
    }

    @Test
    @DisplayName("[TC-AUTH-102] 틀린 비밀번호 / 없는 이메일 → 둘 다 401 같은 메시지 Invalid credentials")
    void login_failure_sameMessage() throws Exception {
        String email = newOperator("Right-Pass!1");
        MvcResult wrongPassword = login(email, "Wrong-Pass!1");
        MvcResult noAccount = login("nobody-" + UUID.randomUUID() + "@test.local", "Right-Pass!1");

        for (MvcResult r : List.of(wrongPassword, noAccount)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(api.read(r).get("message").asText()).isEqualTo("Invalid credentials");
        }
    }

    @Test
    @DisplayName("[TC-AUTH-103] 같은 이메일로 5번 실패한 뒤 6번째는 맞는 비밀번호여도 429 (5번째까지 401)")
    void login_lockedAfterFiveFailures() throws Exception {
        String email = newOperator("Right-Pass!1");
        for (int i = 1; i <= 5; i++) {
            assertThat(login(email, "Wrong-Pass!" + i).getResponse().getStatus()).as("실패 %d번째", i).isEqualTo(401);
        }
        MvcResult sixth = login(email, "Right-Pass!1");
        assertThat(sixth.getResponse().getStatus()).isEqualTo(429);
        assertThat(api.read(sixth).get("message").asText()).isEqualTo("Too many login attempts. Try again later");
    }

    @Test
    @DisplayName("[TC-AUTH-104] 서비스 토큰으로 운영자 API / 운영자 토큰으로 신청 접수 → 둘 다 403 + traceId")
    void roles_doNotMix() throws Exception {
        mockMvc.perform(get("/api/admin/applications").header("Authorization", bearer(serviceToken()))
                .header("X-Trace-Id", "hfm-role-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(jsonPath("$.traceId").value("hfm-role-1"));
        assertThat(receiveWith(api.operatorToken())).isEqualTo(403);
    }

    @Test
    @DisplayName("[TC-AUTH-105] 서비스 토큰의 iss 다름 / aud 다름 / 만료 / 다른 비밀키 / 운영자 비밀키로 서명 → 모두 401")
    void serviceToken_invalidVariants_unauthorized() throws Exception {
        String other = "Some-Other-Secret-Key-That-Is-32bytes-long!!";
        Instant now = Instant.now();
        List<String> tokens = List.of(
                serviceToken("someone-else", "finflow", ApiTestSupport.SERVICE_SECRET, now, 300),
                serviceToken("homefin-mate", "riskwatch", ApiTestSupport.SERVICE_SECRET, now, 300),
                serviceToken("homefin-mate", "finflow", ApiTestSupport.SERVICE_SECRET, now.minusSeconds(400), 300),
                serviceToken("homefin-mate", "finflow", other, now, 300),
                serviceToken("homefin-mate", "finflow", ApiTestSupport.OPERATOR_SECRET, now, 300));
        for (String token : tokens) {
            assertThat(receiveWith(token)).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("[TC-AUTH-106] 서비스 토큰 유효 기간 5분은 통과, 5분 1초는 401")
    void serviceToken_lifetimeBoundary() throws Exception {
        Instant now = Instant.now();
        assertThat(receiveWith(serviceToken("homefin-mate", "finflow", ApiTestSupport.SERVICE_SECRET, now, 300)))
                .isEqualTo(201);
        assertThat(receiveWith(serviceToken("homefin-mate", "finflow", ApiTestSupport.SERVICE_SECRET, now, 301)))
                .isEqualTo(401);
    }

    @Test
    @DisplayName("[TC-AUTH-107] 유효한 운영자 토큰이라도 DB에서 운영자가 삭제됐으면 401")
    void deletedOperatorToken_unauthorized() throws Exception {
        String email = newOperator("Right-Pass!1");
        Long id = operatorRepository.findByEmail(email).orElseThrow().getId();
        String token = operatorTokens.issue(id).token();
        operatorRepository.deleteById(id);

        api.adminGet(token, "/api/admin/applications").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[TC-AUTH-202] 로그인 실패 기록의 이메일은 HMAC 값으로만 저장 (원문 없음)")
    void loginFailure_storesHmacOnly() throws Exception {
        String email = newOperator("Right-Pass!1");
        login(email, "Wrong-Pass!1");

        List<String> hashes = jdbc.queryForList("select email_hash from operator_login_failures", String.class);
        assertThat(hashes).isNotEmpty().allMatch(h -> h.matches("hmac:[0-9a-f]{64}"));
        assertThat(jdbc.queryForObject("select count(*) from operator_login_failures where email_hash like ?",
                Long.class, "%" + email + "%")).isZero();
    }
}
