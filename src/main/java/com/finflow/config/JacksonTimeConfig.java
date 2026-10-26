package com.finflow.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;

// API 응답의 LocalDateTime에 서버 시간대를 붙여서 보냄 (예: 2026-10-25T12:13:04Z)
@Configuration
public class JacksonTimeConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeWithOffset() {
        return builder -> builder.serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                // JVM 기본 시간대(UTC) 기준 시각 → 시간대 포함 ISO 문자열
                gen.writeString(value.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString());
            }
        });
    }
}
