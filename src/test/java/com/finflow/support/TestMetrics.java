package com.finflow.support;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 지표 확인용: 에이전트 대신 메모리에 지표를 모으는 OTel (@Import로 씀)
@TestConfiguration
public class TestMetrics {

    @Bean
    public InMemoryMetricReader metricReader() {
        return InMemoryMetricReader.create();
    }

    @Bean
    @Primary
    public OpenTelemetry testOpenTelemetry(InMemoryMetricReader metricReader) {
        return OpenTelemetrySdk.builder()
                .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
                .build();
    }

    // 지표 이름·속성이 정확히 같은 카운터 값 (누적)
    public static long count(InMemoryMetricReader reader, String name, Attributes attributes) {
        return points(reader, name).filter(p -> p.getAttributes().equals(attributes))
                .mapToLong(LongPointData::getValue).sum();
    }

    // 지표 이름의 모든 속성 합 (누적)
    public static long total(InMemoryMetricReader reader, String name) {
        return points(reader, name).mapToLong(LongPointData::getValue).sum();
    }

    // 지표 이름의 속성 조합 수 (0으로 미리 만든 것 포함)
    public static long series(InMemoryMetricReader reader, String name) {
        return points(reader, name).count();
    }

    private static java.util.stream.Stream<LongPointData> points(InMemoryMetricReader reader, String name) {
        return reader.collectAllMetrics().stream()
                .filter(m -> m.getName().equals(name))
                .flatMap(m -> m.getLongSumData().getPoints().stream());
    }
}
