package com.finflow.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// OTel API 진입점: 에이전트로 띄우면 에이전트가 등록한 것, 아니면 아무 일도 하지 않는 것 (테스트는 메모리용으로 바꿔 끼움)
@Configuration
public class OpenTelemetryConfig {

    @Bean
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }
}
