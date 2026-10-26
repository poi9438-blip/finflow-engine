package com.finflow.service;

import com.finflow.common.BusinessException;
import com.finflow.dto.application.ApplicationReceipt;
import com.finflow.dto.application.CreateApplicationRequest;
import com.finflow.entity.Application;
import com.finflow.security.AuthPrincipal;
import com.finflow.util.RequestFingerprint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.regex.Pattern;

// 신청 접수 멱등성 (업무 명세 7장): 키 확인 → 접수 → 동시 저장 실패면 키로 다시 조회
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentReceiver {

    static final String KEY_REUSED = "Idempotency key reused with different request";
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final ApplicationService applicationService;

    // 접수 결과 (replayed: 처음 결과를 다시 돌려줌)
    public record Result(ApplicationReceipt receipt, boolean replayed) {
    }

    public Result receive(CreateApplicationRequest request, String idempotencyKey, AuthPrincipal actor) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        if (!KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 8 to 64 letters, digits or hyphens");
        }
        String requestHash = RequestFingerprint.of(request);

        // 키를 먼저 확인 (재전송은 진행 중 중복 409보다 우선)
        Optional<Result> replay = replayIfKnown(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return new Result(applicationService.receive(request, idempotencyKey, requestHash, actor), false);
        } catch (ApplicationService.DuplicateSaveException e) {
            // 동시에 들어온 요청이 먼저 저장됨: 같은 키면 재전송, 아니면 진행 중 중복
            return replayIfKnown(idempotencyKey, requestHash)
                    .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, ApplicationService.ACTIVE_EXISTS));
        }
    }

    // 같은 키로 저장된 신청이 있으면: 요청 값이 같으면 재전송, 다르면 409
    private Optional<Result> replayIfKnown(String idempotencyKey, String requestHash) {
        Optional<Application> existing = applicationService.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Application application = existing.get();
        if (!application.getRequestHash().equals(requestHash)) {
            log.warn("Idempotency key reused with different request applicationId={}",
                    application.getApplicationId());
            throw new BusinessException(HttpStatus.CONFLICT, KEY_REUSED);
        }
        log.info("Idempotent replay applicationId={}", application.getApplicationId());
        return Optional.of(new Result(ApplicationReceipt.of(application), true));
    }
}
