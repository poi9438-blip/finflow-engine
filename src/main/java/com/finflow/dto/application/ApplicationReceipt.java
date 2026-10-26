package com.finflow.dto.application;

import com.finflow.domain.ApplicationStatus;

import java.time.LocalDateTime;

// 접수 응답 (외부 신청 번호만, DB 순번 id 없음)
public record ApplicationReceipt(String applicationId, ApplicationStatus status, LocalDateTime receivedAt) {
}
