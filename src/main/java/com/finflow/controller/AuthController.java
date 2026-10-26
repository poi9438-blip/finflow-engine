package com.finflow.controller;

import com.finflow.common.ApiResponse;
import com.finflow.common.TraceIds;
import com.finflow.dto.auth.LoginRequest;
import com.finflow.dto.auth.LoginResponse;
import com.finflow.service.OperatorAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 운영자 로그인 (토큰은 응답 본문으로, 브라우저 쿠키를 쓰지 않음)
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final OperatorAuthService operatorAuthService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(operatorAuthService.login(request), TraceIds.current()));
    }
}
