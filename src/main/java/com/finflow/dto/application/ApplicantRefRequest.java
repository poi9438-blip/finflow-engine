package com.finflow.dto.application;

import jakarta.validation.constraints.NotBlank;

// 신청자 확인이 필요한 요청 (취소)
public record ApplicantRefRequest(@NotBlank(message = "Applicant ref is required") String applicantRef) {
}
