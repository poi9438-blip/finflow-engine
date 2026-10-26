package com.finflow.dto.application;

import com.finflow.domain.ApplicationStatus;
import com.finflow.domain.ProductCode;
import com.finflow.entity.Application;

import java.time.LocalDateTime;

// 신청자 본인 조회 응답 (연소득·DTI·운영자 메모 없음)
public record ApplicantView(String applicationId, ProductCode productCode, ApplicationStatus status,
        Long requestedAmount, Integer termMonths, LocalDateTime receivedAt, LocalDateTime decidedAt,
        String rejectReason) {

    public static ApplicantView of(Application a) {
        return new ApplicantView(a.getApplicationId(), a.getProductCode(), a.getStatus(), a.getRequestedAmount(),
                a.getTermMonths(), a.getReceivedAt(), a.getDecidedAt(), a.getRejectReason());
    }
}
