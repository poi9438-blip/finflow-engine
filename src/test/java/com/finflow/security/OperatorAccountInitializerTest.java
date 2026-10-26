package com.finflow.security;

import com.finflow.entity.Operator;
import com.finflow.repository.OperatorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 서버 시작 시 첫 운영자 계정 만들기
@SpringBootTest
@ActiveProfiles("test")
class OperatorAccountInitializerTest {

    @Autowired
    OperatorAccountInitializer initializer;
    @Autowired
    OperatorRepository operatorRepository;
    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("[TC-AUTH-201] 첫 운영자: 계정 없으면 만듦(BCrypt, 이메일 소문자) / 이미 있으면 비밀번호를 덮어쓰지 않음 / 값이 비면 만들지 않음")
    void createInitialOperator() {
        // 서버 시작 때 application-test.yml 값으로 이미 만들어짐
        Operator seeded = operatorRepository.findByEmail("operator@test.local").orElseThrow();
        assertThat(passwordEncoder.matches("Operator-Test-Pass!1", seeded.getPasswordHash())).isTrue();
        assertThat(seeded.getPasswordHash()).startsWith("$2a$").doesNotContain("Operator-Test-Pass!1");

        String email = "Init-" + UUID.randomUUID().toString().substring(0, 8) + "@Test.Local";
        assertThat(initializer.createInitialOperator(email, "First-Pass!1")).isTrue();
        Operator created = operatorRepository.findByEmail(email.toLowerCase()).orElseThrow();
        assertThat(passwordEncoder.matches("First-Pass!1", created.getPasswordHash())).isTrue();

        // 이미 있으면 그대로 (다른 비밀번호로 덮어쓰지 않음)
        assertThat(initializer.createInitialOperator(email, "Changed-Pass!1")).isFalse();
        Operator again = operatorRepository.findByEmail(email.toLowerCase()).orElseThrow();
        assertThat(passwordEncoder.matches("First-Pass!1", again.getPasswordHash())).isTrue();

        long before = operatorRepository.count();
        assertThat(initializer.createInitialOperator("", "Some-Pass!1")).isFalse();
        assertThat(initializer.createInitialOperator("x@test.local", " ")).isFalse();
        assertThat(operatorRepository.count()).isEqualTo(before);
    }
}
