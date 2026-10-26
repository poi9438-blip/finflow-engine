package com.finflow.dto.application;

import com.finflow.domain.ApplicationStatus;
import com.finflow.domain.ProductCode;
import com.finflow.entity.Application;

import java.time.LocalDateTime;

// 운영자 목록 한 줄 (연소득·DTI 없음, 상세에서만)
public record AdminListItem(String applicationId, ProductCode productCode, String applicantRef,
        ApplicationStatus status, Long requestedAmount, Integer termMonths, LocalDateTime receivedAt,
        LocalDateTime decidedAt) {

    public static AdminListItem of(Application a) {
        return new AdminListItem(a.getApplicationId(), a.getProductCode(), a.getApplicantRef(), a.getStatus(),
                a.getRequestedAmount(), a.getTermMonths(), a.getReceivedAt(), a.getDecidedAt());
    }
}
