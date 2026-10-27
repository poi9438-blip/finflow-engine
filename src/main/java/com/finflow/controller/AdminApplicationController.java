package com.finflow.controller;

import com.finflow.common.ApiResponse;
import com.finflow.common.TraceIds;
import com.finflow.domain.ApplicationStatus;
import com.finflow.domain.ProductCode;
import com.finflow.dto.application.AdminDetail;
import com.finflow.dto.application.AdminListItem;
import com.finflow.dto.application.ApproveRequest;
import com.finflow.dto.application.RejectRequest;
import com.finflow.security.AuthPrincipal;
import com.finflow.service.ApplicationService;
import com.finflow.service.ReviewStartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 운영자용 신청 API (운영자 토큰, 역할 ADMIN만)
@RestController
@RequestMapping("/api/admin/applications")
@RequiredArgsConstructor
public class AdminApplicationController {

    private final ApplicationService applicationService;
    private final ReviewStartService reviewStartService;

    // 목록 (?status=&productCode=&page=0&size=20, size 최대 100)
    @GetMapping
    public ResponseEntity<ApiResponse<List<AdminListItem>>> list(
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) ProductCode productCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(applicationService.list(status, productCode, page, size),
                TraceIds.current()));
    }

    // 상세 (입력값 전체 + 상태 이력)
    @GetMapping("/{applicationId}")
    public ResponseEntity<ApiResponse<AdminDetail>> detail(@PathVariable String applicationId) {
        return ResponseEntity.ok(ApiResponse.success(applicationService.findForAdmin(applicationId),
                TraceIds.current()));
    }

    // 심사 시작 (RiskWatch 판정을 받아 함께 저장, RiskWatch가 응답하지 않아도 시작)
    @PostMapping("/{applicationId}/review")
    public ResponseEntity<ApiResponse<AdminDetail>> review(@PathVariable String applicationId,
            @AuthenticationPrincipal AuthPrincipal actor) {
        return ResponseEntity.ok(ApiResponse.success(reviewStartService.startReview(applicationId, actor),
                TraceIds.current()));
    }

    // 승인 (메모 선택, 본문 없이도 가능)
    @PostMapping("/{applicationId}/approve")
    public ResponseEntity<ApiResponse<AdminDetail>> approve(@PathVariable String applicationId,
            @Valid @RequestBody(required = false) ApproveRequest request, @AuthenticationPrincipal AuthPrincipal actor) {
        String note = request == null ? null : request.note();
        return ResponseEntity.ok(ApiResponse.success(applicationService.approve(applicationId, note, actor),
                TraceIds.current()));
    }

    // 거절 (사유 필수)
    @PostMapping("/{applicationId}/reject")
    public ResponseEntity<ApiResponse<AdminDetail>> reject(@PathVariable String applicationId,
            @Valid @RequestBody RejectRequest request, @AuthenticationPrincipal AuthPrincipal actor) {
        return ResponseEntity.ok(ApiResponse.success(applicationService.reject(applicationId, request.reason(), actor),
                TraceIds.current()));
    }
}
