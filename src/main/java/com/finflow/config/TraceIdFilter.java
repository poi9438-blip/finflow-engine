package com.finflow.config;

import com.finflow.common.TraceIds;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// 요청마다 traceId를 정해 로그(MDC), 응답 헤더, 요청 span 속성에 넣는 필터 (보안 필터보다 먼저 실행)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceIds.resolve(request.getHeader(TraceIds.HEADER));
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        // 요청 span에 업무 traceId 연결 (에이전트가 없으면 Span.current()는 빈 span이라 아무 일도 안 함)
        Span.current().setAttribute(TraceIds.SPAN_ATTRIBUTE, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 스레드 재사용 대비 MDC 정리 (다음 요청 로그에 이전 번호가 남지 않게)
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}
