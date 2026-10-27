package com.finflow.integration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;

// RiskWatch에 보내는 서비스 토큰 발급 (HS256, iss=finflow, aud=riskwatch, 유효 기간 1분)
@Component
public class RiskWatchTokens {

    public static final String ISSUER = "finflow";
    public static final String AUDIENCE = "riskwatch";
    // RiskWatch는 5분까지 받지만, 호출마다 새로 만들므로 짧게
    public static final long LIFETIME_MS = 60 * 1000L;

    private final SecretKey key;

    // 키가 32바이트보다 짧으면 시작 실패 (jjwt WeakKeyException)
    // FinFlow의 다른 비밀키와 같아도 시작 실패: 같으면 RiskWatch 쪽 키를 아는 사람이 FinFlow 토큰도 만들 수 있음
    public RiskWatchTokens(@Value("${app.riskwatch.token-secret}") String secret,
            @Value("${app.service-token.secret}") String serviceTokenSecret,
            @Value("${app.operator.jwt-secret}") String operatorSecret) {
        // Set.of는 같은 값이 있으면 그 값을 메시지에 넣어 예외를 던지므로 쓰지 않음 (RiskWatch 4단계에서 확인)
        if (Stream.of(secret, serviceTokenSecret, operatorSecret).distinct().count() < 3) {
            throw new IllegalStateException(
                    "RISKWATCH_TOKEN_SECRET must differ from SERVICE_TOKEN_SECRET and OPERATOR_JWT_SECRET");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(LIFETIME_MS)))
                .signWith(key)
                .compact();
    }
}
