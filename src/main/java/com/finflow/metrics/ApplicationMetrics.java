package com.finflow.metrics;

import com.finflow.common.AfterCommit;
import com.finflow.domain.ApplicationStatus;
import com.finflow.domain.ProductCode;
import com.finflow.domain.RiskStatus;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import org.springframework.stereotype.Component;

// 업무 지표: 신청 상태 변화(접수 포함), 심사 시작 때 RiskWatch 결과 (Prometheus 이름은 …_total)
@Component
public class ApplicationMetrics {

    static final String STATUS_CHANGES = "finflow.application.status_changes";
    static final String RISKWATCH_EVALUATIONS = "finflow.riskwatch.evaluations";
    static final AttributeKey<String> PRODUCT_CODE = AttributeKey.stringKey("product_code");
    static final AttributeKey<String> TO_STATUS = AttributeKey.stringKey("to_status");
    static final AttributeKey<String> STATUS = AttributeKey.stringKey("status");

    private final LongCounter statusChanges;
    private final LongCounter riskWatchEvaluations;

    public ApplicationMetrics(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.getMeter("finflow");
        this.statusChanges = meter.counterBuilder(STATUS_CHANGES)
                .setDescription("Application status changes saved, including RECEIVED")
                .setUnit("{change}")
                .build();
        this.riskWatchEvaluations = meter.counterBuilder(RISKWATCH_EVALUATIONS)
                .setDescription("RiskWatch results when a review starts")
                .setUnit("{evaluation}")
                .build();
        // 정해진 조합을 0으로 미리 만듦: 처음 생긴 값은 Prometheus increase()가 증가로 보지 않아 첫 건을 놓치므로
        for (ProductCode product : ProductCode.values()) {
            for (ApplicationStatus status : ApplicationStatus.values()) {
                statusChanges.add(0, Attributes.of(PRODUCT_CODE, product.name(), TO_STATUS, status.name()));
            }
        }
        for (RiskStatus status : RiskStatus.values()) {
            riskWatchEvaluations.add(0, Attributes.of(STATUS, status.name()));
        }
    }

    // 상태 이력이 커밋된 뒤에 셈 (재전송·409·롤백은 세지 않음)
    public void statusChanged(ProductCode productCode, ApplicationStatus to) {
        Attributes attributes = Attributes.of(PRODUCT_CODE, productCode.name(), TO_STATUS, to.name());
        AfterCommit.run(() -> statusChanges.add(1, attributes));
    }

    // 심사 시작 때 RiskWatch 결과 (판정 / 응답 없음 / 입력 모자람)
    public void riskEvaluation(RiskStatus status) {
        riskWatchEvaluations.add(1, Attributes.of(STATUS, status.name()));
    }
}
