package com.finflow.dto.application;

import com.finflow.domain.ApplicationStatus;
import com.finflow.entity.Application;

import java.time.LocalDateTime;

// 접수 응답 (외부 신청 번호만, DB 순번 id 없음)
public record ApplicationReceipt(String applicationId, ApplicationStatus status, LocalDateTime receivedAt) {

    // 접수 응답은 항상 접수 당시 그대로 (재전송도 같은 본문, 그 뒤 상태는 조회 API로)
    public static ApplicationReceipt of(Application a) {
        return new ApplicationReceipt(a.getApplicationId(), ApplicationStatus.RECEIVED, a.getReceivedAt());
    }
}
