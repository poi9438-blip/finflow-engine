package com.finflow.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// traceId 규칙 단위 테스트
class TraceIdsTest {

    private static final String FF_TRACE = "ff-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    @DisplayName("[TC-BASE-201] traceId 규칙: 형식에 맞으면 그대로(64자 경계 포함), 없음·빈 값·65자·허용 안 되는 문자는 새 ff-UUID")
    void resolve() {
        assertThat(TraceIds.resolve("hfm-1b2c.3_d")).isEqualTo("hfm-1b2c.3_d");
        assertThat(TraceIds.resolve("a".repeat(64))).isEqualTo("a".repeat(64));

        assertThat(TraceIds.resolve(null)).matches(FF_TRACE);
        assertThat(TraceIds.resolve("  ")).matches(FF_TRACE);
        assertThat(TraceIds.resolve("a".repeat(65))).matches(FF_TRACE);
        assertThat(TraceIds.resolve("hfm-1\nforged log")).matches(FF_TRACE);
        // 매번 새 번호
        assertThat(TraceIds.resolve(null)).isNotEqualTo(TraceIds.resolve(null));
    }
}
