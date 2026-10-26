package com.finflow.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

// 운영자 토큰 발급·확인 (HS256, 비밀키 OPERATOR_JWT_SECRET은 FinFlow만 앎, subject = 운영자 id)
@Component
public class OperatorTokens {

    public static final String ISSUER = "finflow";

    private final SecretKey key;
    private final long expirationMs;

    public OperatorTokens(@Value("${app.operator.jwt-secret}") String secret,
            @Value("${app.operator.token-expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    // 발급한 토큰과 만료 시각
    public record Issued(String token, Instant expiresAt) {
    }

    public Issued issue(Long operatorId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(expirationMs);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(operatorId))
                .claim("role", Role.ADMIN.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new Issued(token, expiresAt);
    }

    // 유효한 운영자 토큰이면 운영자 id (서명·발급자·만료 확인)
    public Optional<Long> operatorId(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Optional.of(Long.valueOf(subject));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
