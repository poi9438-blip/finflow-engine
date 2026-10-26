package com.finflow.config;

import com.finflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 오류 응답 형식 테스트: 테스트용 Controller에서 예외를 일부러 던져 GlobalExceptionHandler 결과 확인
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class ErrorController {
        @GetMapping("/test/business")
        void business() {
            throw new BusinessException(HttpStatus.CONFLICT, "Application already exists");
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("select * from guarantee_applications where secret_column = 1");
        }

        @GetMapping("/test/items/{id}")
        String item(@PathVariable Long id) {
            return "ok";
        }

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody AmountRequest request) {
            return "ok";
        }
    }

    record AmountRequest(@NotNull @Positive Long amount) {
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    @DisplayName("[TC-ERR-201] 업무 오류(BusinessException) → 지정한 상태 코드와 공개 메시지, traceId")
    void businessException() throws Exception {
        mockMvc.perform(get("/test/business").header("X-Trace-Id", "hfm-biz"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value("Application already exists"))
                .andExpect(jsonPath("$.traceId").value("hfm-biz"));
    }

    @Test
    @DisplayName("[TC-ERR-202] 예상 못 한 오류 → 500 + 일반 메시지, 예외 내용(SQL문·테이블 이름)은 응답에 없음")
    void unexpectedExceptionHidesDetails() throws Exception {
        mockMvc.perform(get("/test/unexpected").header("X-Trace-Id", "hfm-500"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.traceId").value("hfm-500"))
                .andExpect(content().string(not(containsString("secret_column"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    @DisplayName("[TC-ERR-203] 입력값 검증 실패 → 400, \"필드: 메시지\"")
    void validationError() throws Exception {
        mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("amount: ")));
    }

    @Test
    @DisplayName("[TC-ERR-204] 깨진 JSON 본문 → 400 \"Malformed request body\" (파서 메시지 노출 없음)")
    void malformedBody() throws Exception {
        mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    @DisplayName("[TC-ERR-205] 주소 값 타입 불일치(숫자 자리에 글자) → 400 \"Invalid parameter: id\"")
    void typeMismatch() throws Exception {
        mockMvc.perform(get("/test/items/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid parameter: id"));
    }

    @Test
    @DisplayName("[TC-ERR-206] 허용하지 않는 메서드 → 405, 500으로 바뀌지 않음")
    void methodNotAllowed() throws Exception {
        mockMvc.perform(post("/test/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message").value("Method Not Allowed"));
    }
}
