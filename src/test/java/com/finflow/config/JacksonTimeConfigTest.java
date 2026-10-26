package com.finflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

// 응답 시각 형식 테스트 (JVM 기본 시간대를 바꿔 가며 확인)
class JacksonTimeConfigTest {

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(original);
    }

    private String serialize(LocalDateTime time) throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonTimeConfig().localDateTimeWithOffset().customize(builder);
        ObjectMapper mapper = builder.build();
        return mapper.writeValueAsString(Map.of("at", time));
    }

    @Test
    @DisplayName("[TC-BASE-202] 응답의 LocalDateTime에 서버 시간대가 붙음 (UTC는 Z, 서울은 +09:00)")
    void writesOffset() throws Exception {
        LocalDateTime time = LocalDateTime.of(2026, 10, 26, 12, 13, 4);

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        assertThat(serialize(time)).isEqualTo("{\"at\":\"2026-10-26T12:13:04Z\"}");

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        assertThat(serialize(time)).isEqualTo("{\"at\":\"2026-10-26T12:13:04+09:00\"}");
    }
}
