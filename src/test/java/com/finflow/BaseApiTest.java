package com.finflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 뼈대 HTTP 테스트: 보안 필터·traceId 필터·actuator를 실제 설정 그대로 확인
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BaseApiTest {

    private static final String FF_TRACE = "ff-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("[TC-BASE-101] health는 로그인 없이 200, X-Trace-Id 헤더가 없으면 ff-UUID를 만들어 응답 헤더에 넣음")
    void healthIsPublicAndGetsTraceId() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // 세부 정보(DB 등 구성 요소)는 숨김
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(header().string("X-Trace-Id", matchesPattern(FF_TRACE)));
    }

    @Test
    @DisplayName("[TC-BASE-102] HomeFin이 보낸 X-Trace-Id(hfm-…)는 그대로 응답 헤더와 오류 본문에 씀")
    void keepsIncomingTraceId() throws Exception {
        mockMvc.perform(get("/api/applications").header("X-Trace-Id", "hfm-1234abcd"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Trace-Id", "hfm-1234abcd"))
                .andExpect(jsonPath("$.traceId").value("hfm-1234abcd"));
    }

    @Test
    @DisplayName("[TC-BASE-103] 형식이 틀린 X-Trace-Id(허용 안 되는 문자 / 65자)는 버리고 새 ff-UUID 사용")
    void replacesInvalidTraceId() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Trace-Id", "hfm-<script>"))
                .andExpect(header().string("X-Trace-Id", matchesPattern(FF_TRACE)));
        mockMvc.perform(get("/actuator/health").header("X-Trace-Id", "a".repeat(65)))
                .andExpect(header().string("X-Trace-Id", matchesPattern(FF_TRACE)));
    }

    @Test
    @DisplayName("[TC-BASE-104] 인증 없이 API 호출 → 401, 공통 오류 형식(status·message·traceId), traceId는 응답 헤더와 같음")
    void unauthorizedJsonWithTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/applications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value("Unauthorized"))
                .andReturn();
        String headerTrace = result.getResponse().getHeader("X-Trace-Id");
        assertThat(headerTrace).matches(FF_TRACE);
        assertThat(result.getResponse().getContentAsString()).contains("\"traceId\":\"" + headerTrace + "\"");
    }

    @Test
    @WithMockUser
    @DisplayName("[TC-BASE-105] 인증된 요청이라도 actuator는 health만 공개 (env, beans는 404)")
    void actuatorExposesHealthOnly() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("[TC-BASE-106] 없는 주소 → 404, 공통 오류 형식 + traceId")
    void notFoundJsonWithTraceId() throws Exception {
        mockMvc.perform(get("/api/no-such-path").header("X-Trace-Id", "hfm-notfound"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value("Not Found"))
                .andExpect(jsonPath("$.traceId").value("hfm-notfound"));
    }

    @Test
    @DisplayName("[TC-BASE-107] 요청이 끝나면 로그용 traceId(MDC)를 지움 (같은 스레드의 다음 요청에 남지 않음)")
    void clearsMdcAfterRequest() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Trace-Id", "hfm-mdc"));
        assertThat(MDC.get("traceId")).isNull();
    }
}
