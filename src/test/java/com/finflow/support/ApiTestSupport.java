package com.finflow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// HTTP 통합 테스트 공통 도우미 (서비스 토큰 만들기, 운영자 로그인, 신청 요청)
public final class ApiTestSupport {

    // application-test.yml의 테스트 전용 값
    public static final String SERVICE_SECRET = "FinFlow-Test-Service-Token-Secret-32bytes-or-more";
    public static final String OPERATOR_SECRET = "FinFlow-Test-Operator-JWT-Secret-32bytes-or-more";
    public static final String OPERATOR_EMAIL = "operator@test.local";
    public static final String OPERATOR_PASSWORD = "Operator-Test-Pass!1";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public ApiTestSupport(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    // HomeFin 서버가 만드는 것과 같은 서비스 토큰 (발급자, 대상, 비밀키, 발급 시각, 유효 기간을 바꿔 가며 확인)
    public static String serviceToken(String issuer, String audience, String secret, Instant issuedAt,
            long lifetimeSeconds) {
        return Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject("homefin-mate")
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(lifetimeSeconds)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    // 정상 서비스 토큰 (유효 기간 5분)
    public static String serviceToken() {
        return serviceToken("homefin-mate", "finflow", SERVICE_SECRET, Instant.now(), 300);
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    // 겹치지 않는 신청자 (homefin:{숫자})
    public static String applicantRef() {
        return "homefin:" + ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 10);
    }

    // 정상 신청 본문 (값을 바꿔 경계를 확인할 수 있게 Map으로)
    public static Map<String, Object> applicationBody(String applicantRef) {
        Map<String, Object> body = new HashMap<>();
        body.put("productCode", "BOGEUMJARI");
        body.put("applicantRef", applicantRef);
        body.put("requestedAmount", 300_000_000L);
        body.put("termMonths", 360);
        body.put("housePrice", 500_000_000L);
        body.put("householdAnnualIncome", 60_000_000L);
        body.put("dti", "0.3041");
        body.put("firstHome", true);
        body.put("newlywed", false);
        body.put("newbornWithin2Years", false);
        body.put("childCount", 1);
        body.put("region", "부산");
        body.put("simulationRef", 42);
        return body;
    }

    // 서비스 토큰으로 신청 접수
    public ResultActions receive(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/applications")
                .header("Authorization", bearer(serviceToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    // 접수하고 applicationId 반환
    public String receiveOk(Map<String, Object> body) throws Exception {
        return read(receive(body).andReturn()).at("/data/applicationId").asText();
    }

    // 운영자 로그인 → 토큰
    public String operatorToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))))
                .andReturn();
        return read(result).at("/data/token").asText();
    }

    public String operatorToken() throws Exception {
        return operatorToken(OPERATOR_EMAIL, OPERATOR_PASSWORD);
    }

    // 운영자 API 호출 (POST, 본문 선택)
    public ResultActions adminPost(String token, String path, Object body) throws Exception {
        var request = post(path).header("Authorization", bearer(token));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        return mockMvc.perform(request);
    }

    public ResultActions adminGet(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", bearer(token)));
    }

    public String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    public JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
