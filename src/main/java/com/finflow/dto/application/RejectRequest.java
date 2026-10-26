package com.finflow.dto.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 거절 요청 (사유 필수)
public record RejectRequest(
        @NotBlank(message = "Reason is required")
        @Size(max = 500, message = "Reason must be at most 500 characters") String reason) {
}
