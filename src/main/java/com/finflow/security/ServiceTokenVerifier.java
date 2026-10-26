package com.finflow.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

// HomeFin 서버의 서비스 토큰 확인 (HS256, iss=homefin-mate, aud=finflow, 유효 기간 5분 이하)
@Component
public class ServiceTokenVerifier {

    public static final String ISSUER = "homefin-mate";
    public static final String AUDIENCE = "finflow";
    // 발급 시각부터 만료 시각까지 최대 5분 (오래 쓰는 토큰은 새어 나가도 쓸 수 있는 시간이 길어서 거부)
    public static final long MAX_LIFETIME_MS = 5 * 60 * 1000L;

    private final SecretKey key;

    // 키가 32바이트(256비트)보다 짧으면 서버 시작 실패 (jjwt WeakKeyException)
    public ServiceTokenVerifier(@Value("${app.service-token.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // 서명·발급자·대상·만료·유효 기간이 모두 맞으면 true
    public boolean isValid(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Date issuedAt = claims.getIssuedAt();
            Date expiration = claims.getExpiration();
            return issuedAt != null && expiration != null
                    && expiration.getTime() - issuedAt.getTime() <= MAX_LIFETIME_MS;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
