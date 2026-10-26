package com.finflow.security;

import com.finflow.entity.Operator;
import com.finflow.repository.OperatorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;

// 서버 시작 시 첫 운영자 계정 만들기 (OPERATOR_EMAIL·OPERATOR_PASSWORD, 이미 있으면 그대로, 비우면 안 만듦)
@Slf4j
@Component
@RequiredArgsConstructor
public class OperatorAccountInitializer implements ApplicationRunner {

    private final OperatorRepository operatorRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.operator.initial-email}")
    private String initialEmail;

    @Value("${app.operator.initial-password}")
    private String initialPassword;

    @Override
    public void run(ApplicationArguments args) {
        createInitialOperator(initialEmail, initialPassword);
    }

    // 계정을 만들었으면 true
    boolean createInitialOperator(String email, String password) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            log.info("Initial operator not configured");
            return false;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (operatorRepository.findByEmail(normalized).isPresent()) {
            return false;
        }
        Operator operator = operatorRepository.save(Operator.builder()
                .email(normalized)
                .passwordHash(passwordEncoder.encode(password))
                .createdAt(LocalDateTime.now())
                .build());
        log.info("Initial operator created operatorId={}", operator.getId());
        return true;
    }
}
