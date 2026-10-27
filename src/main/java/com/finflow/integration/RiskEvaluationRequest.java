package com.finflow.integration;

import com.finflow.entity.Application;

import java.math.BigDecimal;

// RiskWatch 평가 요청 본문 (RiskWatch 업무 명세 3장, 요청 서버는 토큰으로 정하므로 넣지 않음)
public record RiskEvaluationRequest(String productCode, String applicantRef, String sourceRef, long requestedAmount,
        int termMonths, long housePrice, long householdAnnualIncome, BigDecimal dti, String houseOwnership,
        boolean firstHome, boolean newlywed, boolean newbornWithin2Years, boolean dualIncome, int childCount,
        String region) {

    // 신청 값으로 만듦 (판정 입력이 모두 있는 신청만, sourceRef는 신청 번호)
    public static RiskEvaluationRequest of(Application a) {
        return new RiskEvaluationRequest(a.getProductCode().name(), a.getApplicantRef(), a.getApplicationId(),
                a.getRequestedAmount(), a.getTermMonths(), a.getHousePrice(),
                a.getHouseholdAnnualIncome().longValueExact(), a.getDti(), a.getHouseOwnership().name(),
                a.getFirstHome(), a.getNewlywed(), a.getNewbornWithin2Years(), a.getDualIncome(), a.getChildCount(),
                a.getRegion());
    }
}
