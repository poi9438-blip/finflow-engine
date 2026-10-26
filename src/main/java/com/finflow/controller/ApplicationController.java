package com.finflow.controller;

import com.finflow.common.ApiResponse;
import com.finflow.common.TraceIds;
import com.finflow.dto.application.ApplicantRefRequest;
import com.finflow.dto.application.ApplicantView;
import com.finflow.dto.application.ApplicationReceipt;
import com.finflow.dto.application.CreateApplicationRequest;
import com.finflow.security.AuthPrincipal;
import com.finflow.service.ApplicationService;
import com.finflow.service.IdempotentReceiver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// HomeFin 서버용 신청 API (서비스 토큰, 역할 SERVICE만)
@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;
    private final IdempotentReceiver idempotentReceiver;

    // 신청 접수 (새 신청 201, 같은 키 재전송은 200 + Idempotent-Replayed: true, 업무 명세 7장)
    @PostMapping
    public ResponseEntity<ApiResponse<ApplicationReceipt>> receive(@Valid @RequestBody CreateApplicationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthPrincipal actor) {
        IdempotentReceiver.Result result = idempotentReceiver.receive(request, idempotencyKey, actor);
        ApiResponse<ApplicationReceipt> body = ApiResponse.success(result.receipt(), TraceIds.current());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // 신청자 본인 신청 조회
    @GetMapping("/{applicationId}")
    public ResponseEntity<ApiResponse<ApplicantView>> find(@PathVariable String applicationId,
            @RequestParam String applicantRef) {
        return ResponseEntity.ok(ApiResponse.success(applicationService.findForApplicant(applicationId, applicantRef),
                TraceIds.current()));
    }

    // 신청자 취소
    @PostMapping("/{applicationId}/cancel")
    public ResponseEntity<ApiResponse<ApplicantView>> cancel(@PathVariable String applicationId,
            @Valid @RequestBody ApplicantRefRequest request, @AuthenticationPrincipal AuthPrincipal actor) {
        return ResponseEntity.ok(ApiResponse.success(
                applicationService.cancel(applicationId, request.applicantRef(), actor), TraceIds.current()));
    }
}
