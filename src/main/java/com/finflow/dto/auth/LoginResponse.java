package com.finflow.dto.auth;

import java.time.Instant;

// 운영자 로그인 응답 (토큰, 만료 시각)
public record LoginResponse(String token, Instant expiresAt) {
}
