package com.finflow.integration;

import com.finflow.domain.RiskStatus;

import java.time.LocalDateTime;

// RiskWatch 판정 결과 (받은 판정, 또는 받지 못한 이유)
public record RiskResult(RiskStatus status, String evaluationId, String verdict, String criteriaVersion,
        LocalDateTime evaluatedAt, String failure) {

    public static RiskResult evaluated(String evaluationId, String verdict, String criteriaVersion,
            LocalDateTime evaluatedAt) {
        return new RiskResult(RiskStatus.EVALUATED, evaluationId, verdict, criteriaVersion, evaluatedAt, null);
    }

    public static RiskResult unavailable(String failure) {
        return new RiskResult(RiskStatus.UNAVAILABLE, null, null, null, null, failure);
    }

    public static RiskResult inputMissing() {
        return new RiskResult(RiskStatus.INPUT_MISSING, null, null, null, null, null);
    }
}
