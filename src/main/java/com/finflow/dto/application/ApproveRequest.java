package com.finflow.dto.application;

import jakarta.validation.constraints.Size;

// 승인 요청 (메모는 선택)
public record ApproveRequest(@Size(max = 500, message = "Note must be at most 500 characters") String note) {
}
