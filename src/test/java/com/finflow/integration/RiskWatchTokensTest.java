package com.finflow.integration;

import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// RiskWatch 토큰 비밀키 시작 검사 (서버 없이 생성자만)
class RiskWatchTokensTest {

    private static final String RISKWATCH = "RiskWatch-Token-Test-Secret-32bytes-long!!";
    private static final String SERVICE = "Service-Token-Test-Secret-32bytes-long!!!!";
    private static final String OPERATOR = "Operator-Token-Test-Secret-32bytes-long!!!";

    @Test
    @DisplayName("[TC-RISK-201] RISKWATCH_TOKEN_SECRET이 SERVICE_TOKEN_SECRET·OPERATOR_JWT_SECRET과 같거나 32바이트보다 짧으면 시작 실패, 메시지에 키 값 없음")
    void secretMustDifferAndBeLongEnough() {
        assertThatCode(() -> new RiskWatchTokens(RISKWATCH, SERVICE, OPERATOR)).doesNotThrowAnyException();
        for (String[] same : new String[][] { { SERVICE, SERVICE, OPERATOR }, { OPERATOR, SERVICE, OPERATOR } }) {
            assertThatThrownBy(() -> new RiskWatchTokens(same[0], same[1], same[2]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must differ")
                    .hasMessageNotContaining(same[0]);
        }
        assertThatThrownBy(() -> new RiskWatchTokens("short-riskwatch-secret", SERVICE, OPERATOR))
                .isInstanceOf(WeakKeyException.class);
    }
}
