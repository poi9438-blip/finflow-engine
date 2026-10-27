package com.finflow.dto.application;

import com.finflow.domain.ActorType;
import com.finflow.domain.ApplicationStatus;
import com.finflow.domain.ApplicationType;
import com.finflow.domain.HouseOwnership;
import com.finflow.domain.ProductCode;
import com.finflow.domain.RiskStatus;
import com.finflow.entity.Application;
import com.finflow.entity.ApplicationStatusHistory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 운영자 상세 (입력값 전체 + RiskWatch 판정 + 상태 이력)
public record AdminDetail(String applicationId, ApplicationType type, ProductCode productCode, String applicantRef,
        ApplicationStatus status, Long requestedAmount, Integer termMonths, Long housePrice,
        BigDecimal householdAnnualIncome, BigDecimal dti, Boolean firstHome, Boolean newlywed,
        Boolean newbornWithin2Years, Integer childCount, HouseOwnership houseOwnership, Boolean dualIncome,
        String region, Long simulationRef, String rejectReason, String decisionNote, LocalDateTime receivedAt,
        LocalDateTime decidedAt, Risk risk, List<HistoryItem> history) {

    // 심사 시작 때 받은 RiskWatch 판정 (심사를 시작하기 전이면 risk 자체가 null)
    public record Risk(RiskStatus status, String evaluationId, String verdict, String criteriaVersion,
            LocalDateTime evaluatedAt, String failure) {

        static Risk of(Application a) {
            if (a.getRiskStatus() == null) {
                return null;
            }
            return new Risk(a.getRiskStatus(), a.getRiskEvaluationId(), a.getRiskVerdict(),
                    a.getRiskCriteriaVersion(), a.getRiskEvaluatedAt(), a.getRiskFailure());
        }
    }

    // 상태 이력 한 줄
    public record HistoryItem(ApplicationStatus fromStatus, ApplicationStatus toStatus, ActorType actorType,
            String actorId, String reason, String traceId, LocalDateTime createdAt) {

        static HistoryItem of(ApplicationStatusHistory h) {
            return new HistoryItem(h.getFromStatus(), h.getToStatus(), h.getActorType(), h.getActorId(),
                    h.getReason(), h.getTraceId(), h.getCreatedAt());
        }
    }

    public static AdminDetail of(Application a, List<ApplicationStatusHistory> history) {
        return new AdminDetail(a.getApplicationId(), a.getType(), a.getProductCode(), a.getApplicantRef(),
                a.getStatus(), a.getRequestedAmount(), a.getTermMonths(), a.getHousePrice(),
                a.getHouseholdAnnualIncome(), a.getDti(), a.getFirstHome(), a.getNewlywed(),
                a.getNewbornWithin2Years(), a.getChildCount(), a.getHouseOwnership(), a.getDualIncome(),
                a.getRegion(), a.getSimulationRef(), a.getRejectReason(), a.getDecisionNote(), a.getReceivedAt(),
                a.getDecidedAt(), Risk.of(a), history.stream().map(HistoryItem::of).toList());
    }
}
