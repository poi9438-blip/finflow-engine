package com.finflow.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

// RiskWatch 평가 호출 (업무 명세 13장). 실패해도 예외를 던지지 않고 UNAVAILABLE + 짧은 이유를 돌려줌
// 연결 실패·5xx는 한 번 더, 시간 초과·4xx는 다시 보내지 않음. 서킷 브레이커는 두지 않음 (운영자가 한 건씩 부르는 호출)
@Slf4j
@Component
public class RiskWatchClient {

    private static final Set<String> VERDICTS = Set.of("ELIGIBLE", "REVIEW", "INELIGIBLE");
    private static final int MAX_ATTEMPTS = 2;

    private final RestTemplate restTemplate;
    private final RiskWatchTokens tokens;
    private final String evaluateUrl;
    private final long retryBackoffMs;

    public RiskWatchClient(RestTemplateBuilder builder, RiskWatchTokens tokens,
            @Value("${app.riskwatch.base-url}") String baseUrl,
            @Value("${app.riskwatch.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${app.riskwatch.read-timeout-ms:3000}") long readTimeoutMs,
            @Value("${app.riskwatch.retry-backoff-ms:200}") long retryBackoffMs) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
        this.tokens = tokens;
        this.evaluateUrl = baseUrl + "/api/evaluations";
        this.retryBackoffMs = retryBackoffMs;
    }

    // 평가 요청 (traceId는 FinFlow 감사 기록과 RiskWatch 평가 기록을 잇는 값)
    public RiskResult evaluate(RiskEvaluationRequest request, String traceId) {
        String failure = "error";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return toResult(post(request, traceId));
            } catch (ResourceAccessException e) {
                // 시간 초과는 RiskWatch가 이미 처리 중일 수 있어 다시 보내지 않음 (평가 기록이 둘 생김)
                if (e.getCause() instanceof SocketTimeoutException) {
                    failure = "timeout";
                    break;
                }
                failure = "connection";
            } catch (HttpServerErrorException e) {
                failure = "HTTP " + e.getStatusCode().value();
            } catch (HttpClientErrorException e) {
                // 4xx는 다시 보내도 같음 (입력·토큰 문제)
                failure = "HTTP " + e.getStatusCode().value();
                break;
            } catch (InvalidResponseException e) {
                failure = "invalid response";
                break;
            } catch (RuntimeException e) {
                failure = "error";
                break;
            }
            if (attempt < MAX_ATTEMPTS) {
                sleep(retryBackoffMs);
            }
        }
        // 응답 본문·입력값은 로그에 남기지 않음
        log.warn("RiskWatch evaluation unavailable applicationId={} failure={}", request.sourceRef(), failure);
        return RiskResult.unavailable(failure);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(RiskEvaluationRequest request, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(tokens.issue());
        if (traceId != null) {
            headers.set("X-Trace-Id", traceId);
        }
        return restTemplate.postForObject(evaluateUrl, new HttpEntity<>(request, headers), Map.class);
    }

    // 응답 data에서 평가 번호·판정·기준 버전·시각 (모양이 다르면 InvalidResponseException)
    @SuppressWarnings("unchecked")
    private static RiskResult toResult(Map<String, Object> body) {
        if (body == null || !(body.get("data") instanceof Map<?, ?> raw)) {
            throw new InvalidResponseException();
        }
        Map<String, Object> data = (Map<String, Object>) raw;
        String evaluationId = text(data, "evaluationId", 36);
        String verdict = text(data, "verdict", 20);
        String criteriaVersion = text(data, "criteriaVersion", 30);
        if (!VERDICTS.contains(verdict)) {
            throw new InvalidResponseException();
        }
        return RiskResult.evaluated(evaluationId, verdict, criteriaVersion, evaluatedAt(data.get("createdAt")));
    }

    private static String text(Map<String, Object> data, String field, int maxLength) {
        Object value = data.get(field);
        if (!(value instanceof String s) || s.isBlank() || s.length() > maxLength) {
            throw new InvalidResponseException();
        }
        return s;
    }

    // RiskWatch 시각(…Z) → FinFlow 저장 기준(UTC, 마이크로초)
    private static LocalDateTime evaluatedAt(Object value) {
        try {
            return OffsetDateTime.parse(String.valueOf(value)).withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime().truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException e) {
            throw new InvalidResponseException();
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // RiskWatch 응답 모양이 예상과 다름
    static class InvalidResponseException extends RuntimeException {
        InvalidResponseException() {
            super("Invalid RiskWatch response", null, false, false);
        }
    }
}
