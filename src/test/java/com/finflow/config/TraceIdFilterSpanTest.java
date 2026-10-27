package com.finflow.config;

import com.finflow.common.TraceIds;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

// 요청 span에 업무 traceId 연결 (OTel trace id와 업무 번호를 함께 찾기 위함)
class TraceIdFilterSpanTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    private final TraceIdFilter filter = new TraceIdFilter();

    // 에이전트가 만든 요청 span 역할을 하는 span 안에서 필터 실행 후, 그 span의 속성 반환
    private String traceIdOnSpan(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        Span span = otel.getOpenTelemetry().getTracer("test").spanBuilder("POST /api/test").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            filter.doFilter(request, response, new MockFilterChain());
        } finally {
            span.end();
        }
        // 속성 이름은 상수가 아닌 글자로 확인 (세 서비스가 같은 이름이어야 Tempo에서 함께 찾음)
        return otel.getSpans().get(otel.getSpans().size() - 1).getAttributes()
                .get(AttributeKey.stringKey("hfm.trace_id"));
    }

    @Test
    @DisplayName("[TC-OBS-201] 요청 span에 hfm.trace_id 속성: 받은 X-Trace-Id 또는 만든 ff- 값, 응답 헤더와 같은 값")
    void requestSpan_hasTraceIdAttribute() throws Exception {
        MockHttpServletRequest withHeader = new MockHttpServletRequest("POST", "/api/test");
        withHeader.addHeader(TraceIds.HEADER, "hfm-span-test");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        assertThat(traceIdOnSpan(withHeader, res1)).isEqualTo("hfm-span-test").isEqualTo(res1.getHeader(TraceIds.HEADER));

        MockHttpServletResponse res2 = new MockHttpServletResponse();
        String generated = traceIdOnSpan(new MockHttpServletRequest("POST", "/api/test"), res2);
        assertThat(generated).startsWith("ff-").isEqualTo(res2.getHeader(TraceIds.HEADER));
    }
}
