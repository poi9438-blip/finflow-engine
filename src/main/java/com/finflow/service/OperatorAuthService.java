package com.finflow.service;

import com.finflow.common.BusinessException;
import com.finflow.dto.auth.LoginRequest;
import com.finflow.dto.auth.LoginResponse;
import com.finflow.entity.Operator;
import com.finflow.entity.OperatorLoginFailure;
import com.finflow.repository.OperatorLoginFailureRepository;
import com.finflow.repository.OperatorRepository;
import com.finflow.security.OperatorTokens;
import com.finflow.util.EmailHasher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

// 운영자 로그인 (시도 제한: 최근 15분 안의 실패가 5번 이상이면 429, HomeFin과 같은 기준)
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorAuthService {

    static final int MAX_FAILURES = 5;
    static final int WINDOW_MINUTES = 15;

    private final OperatorRepository operatorRepository;
    private final OperatorLoginFailureRepository failureRepository;
    private final PasswordEncoder passwordEncoder;
    private final OperatorTokens operatorTokens;
    private final EmailHasher emailHasher;

    // 없는 이메일도 비밀번호 확인을 한 번 해서 응답 시간으로 계정 존재를 알 수 없게 함
    // (형식이 틀린 해시는 확인을 바로 건너뛰므로, 서버 시작 때 실제 BCrypt 해시를 만들어 둠)
    private String dummyHash;

    @PostConstruct
    void createDummyHash() {
        dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // 로그인 (실패 기록은 401이어도 남아야 해서 트랜잭션으로 묶지 않음)
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String emailHash = emailHasher.hash(email);
        long recentFailures = failureRepository.countByEmailHashAndCreatedAtAfter(emailHash,
                LocalDateTime.now().minusMinutes(WINDOW_MINUTES));
        if (recentFailures >= MAX_FAILURES) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts. Try again later");
        }

        Operator operator = operatorRepository.findByEmail(email).orElse(null);
        String hash = operator != null ? operator.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (operator == null || !matches) {
            failureRepository.save(OperatorLoginFailure.builder()
                    .emailHash(emailHash)
                    .createdAt(LocalDateTime.now())
                    .build());
            log.warn("Operator login failed emailHash={}", emailHash);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        OperatorTokens.Issued issued = operatorTokens.issue(operator.getId());
        log.info("Operator logged in operatorId={}", operator.getId());
        return new LoginResponse(issued.token(), issued.expiresAt());
    }
}
