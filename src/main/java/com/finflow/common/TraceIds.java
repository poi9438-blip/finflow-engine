package com.finflow.common;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.regex.Pattern;

// 요청 traceId: X-Trace-Id 헤더가 형식에 맞으면 그대로, 없거나 틀리면 ff-UUID 생성
public final class TraceIds {

    // 헤더 이름, 로그(MDC) 키 (HomeFin과 같음)
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    // OTel 요청 span 속성 이름 (HomeFin·RiskWatch와 같음, Tempo에서 세 서비스를 속성 하나로 검색)
    public static final String SPAN_ATTRIBUTE = "hfm.trace_id";

    // FinFlow가 직접 만든 번호의 접두사
    public static final String PREFIX = "ff-";

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private TraceIds() {
    }

    // 헤더 값이 형식에 맞으면 그대로, 아니면 새 ff-UUID
    public static String resolve(String headerValue) {
        return StringUtils.hasText(headerValue) && ALLOWED.matcher(headerValue).matches()
                ? headerValue
                : PREFIX + UUID.randomUUID();
    }

    // 현재 요청의 traceId (TraceIdFilter가 요청 시작 때 MDC에 넣은 값)
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
