package com.finflow.service;

import com.finflow.common.BusinessException;
import com.finflow.common.TraceIds;
import com.finflow.domain.ActorType;
import com.finflow.domain.ApplicationStatus;
import com.finflow.domain.ApplicationType;
import com.finflow.domain.ProductCode;
import com.finflow.dto.application.AdminDetail;
import com.finflow.dto.application.AdminListItem;
import com.finflow.dto.application.ApplicantView;
import com.finflow.dto.application.ApplicationReceipt;
import com.finflow.dto.application.CreateApplicationRequest;
import com.finflow.entity.Application;
import com.finflow.entity.ApplicationStatusHistory;
import com.finflow.repository.ApplicationRepository;
import com.finflow.repository.ApplicationStatusHistoryRepository;
import com.finflow.security.AuthPrincipal;
import com.finflow.security.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// 신청 접수·조회·취소(HomeFin 서버), 심사 시작·승인·거절·목록·상세(운영자) (업무 명세 3~6장)
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

    static final String ACTIVE_EXISTS = "Active application already exists";
    static final String NOT_FOUND = "Application not found";
    // 목록 한 페이지 최대 건수
    public static final int MAX_PAGE_SIZE = 100;

    private static final Set<ApplicationStatus> ACTIVE = EnumSet.of(ApplicationStatus.RECEIVED,
            ApplicationStatus.REVIEWING);

    private final ApplicationRepository applicationRepository;
    private final ApplicationStatusHistoryRepository historyRepository;

    // 신청 접수: 진행 중인 같은 신청자·상품 신청이 있으면 409, 신청과 접수 이력을 한 트랜잭션으로 저장
    // (DB 제약에 막히면 DuplicateSaveException)
    @Transactional
    public ApplicationReceipt receive(CreateApplicationRequest request, String idempotencyKey, String requestHash,
            AuthPrincipal actor) {
        ProductCode productCode = ProductCode.valueOf(request.productCode());
        if (applicationRepository.existsByApplicantRefAndProductCodeAndStatusIn(request.applicantRef(), productCode,
                ACTIVE)) {
            throw new BusinessException(HttpStatus.CONFLICT, ACTIVE_EXISTS);
        }
        LocalDateTime now = now();
        Application application = Application.builder()
                .applicationId(UUID.randomUUID().toString())
                .type(ApplicationType.LOAN)
                .productCode(productCode)
                .applicantRef(request.applicantRef())
                .requestedAmount(request.requestedAmount())
                .termMonths(request.termMonths())
                .housePrice(request.housePrice())
                .householdAnnualIncome(BigDecimal.valueOf(request.householdAnnualIncome()))
                .dti(request.dti())
                .firstHome(request.firstHome())
                .newlywed(request.newlywed())
                .newbornWithin2Years(request.newbornWithin2Years())
                .childCount(request.childCount())
                .region(request.region())
                .simulationRef(request.simulationRef())
                .status(ApplicationStatus.RECEIVED)
                .receivedAt(now)
                .updatedAt(now)
                .traceId(TraceIds.current())
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .build();

        // 동시에 들어온 같은 신청은 DB unique 제약(active_key)이 막음 → 409
        try {
            applicationRepository.saveAndFlush(application);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateSaveException();
        }
        recordHistory(application, null, actor, null, now);
        // 로그에는 금액·소득·DTI를 남기지 않음 (업무 명세 10장)
        log.info("Application received applicationId={} product={}", application.getApplicationId(), productCode);
        return ApplicationReceipt.of(application);
    }

    // 멱등성 키로 저장된 신청 (재전송 확인, 새 트랜잭션)
    @Transactional(readOnly = true)
    public Optional<Application> findByIdempotencyKey(String idempotencyKey) {
        return applicationRepository.findByIdempotencyKey(idempotencyKey);
    }

    // 저장이 DB unique 제약(같은 키 또는 진행 중 중복)에 막힘
    public static class DuplicateSaveException extends RuntimeException {
        DuplicateSaveException() {
            super("Duplicate application save", null, false, false);
        }
    }

    // 신청자 본인 조회 (applicantRef가 다르면 없는 신청처럼 404)
    @Transactional(readOnly = true)
    public ApplicantView findForApplicant(String applicationId, String applicantRef) {
        return ApplicantView.of(findOwned(applicationId, applicantRef));
    }

    // 신청자 취소 (접수·심사 중만, 이미 취소됐으면 그대로 200)
    @Transactional
    public ApplicantView cancel(String applicationId, String applicantRef, AuthPrincipal actor) {
        Application application = findOwned(applicationId, applicantRef);
        if (application.getStatus() != ApplicationStatus.CANCELLED) {
            transition(application, ApplicationStatus.CANCELLED, actor, null);
        }
        return ApplicantView.of(application);
    }

    // 심사 시작 (접수 → 심사 중)
    @Transactional
    public AdminDetail startReview(String applicationId, AuthPrincipal actor) {
        Application application = find(applicationId);
        transition(application, ApplicationStatus.REVIEWING, actor, null);
        return detail(application);
    }

    // 승인 (심사 중 → 승인, 메모 선택)
    @Transactional
    public AdminDetail approve(String applicationId, String note, AuthPrincipal actor) {
        Application application = find(applicationId);
        // 메모를 먼저 넣고 상태와 한 번에 저장 (상태 저장 뒤에 바꾸면 커밋 때 한 번 더 저장되어 버전이 두 번 오름)
        application.recordDecisionNote(note);
        transition(application, ApplicationStatus.APPROVED, actor, note);
        return detail(application);
    }

    // 거절 (심사 중 → 거절, 사유 필수)
    @Transactional
    public AdminDetail reject(String applicationId, String reason, AuthPrincipal actor) {
        Application application = find(applicationId);
        application.recordRejectReason(reason);
        transition(application, ApplicationStatus.REJECTED, actor, reason);
        return detail(application);
    }

    // 운영자 목록 (상태·상품 필터, 최신순, page는 0부터, size는 1~100)
    @Transactional(readOnly = true)
    public List<AdminListItem> list(ApplicationStatus status, ProductCode productCode, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "page must be 0 or more and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return applicationRepository.search(status, productCode, PageRequest.of(page, size)).stream()
                .map(AdminListItem::of)
                .toList();
    }

    // 운영자 상세 (입력값 전체 + 상태 이력)
    @Transactional(readOnly = true)
    public AdminDetail findForAdmin(String applicationId) {
        return detail(find(applicationId));
    }

    // 상태 전이: 규칙 확인 → 상태 저장(버전 확인) → 이력 저장 (한 트랜잭션)
    private void transition(Application application, ApplicationStatus to, AuthPrincipal actor, String reason) {
        ApplicationStatus from = application.getStatus();
        if (!ApplicationStatus.canTransition(from, to)) {
            log.warn("Rejected status transition applicationId={} {} -> {}", application.getApplicationId(), from,
                    to);
            throw new BusinessException(HttpStatus.CONFLICT, "Invalid status transition: " + from + " -> " + to);
        }
        LocalDateTime now = now();
        application.changeStatus(to, now);
        applicationRepository.saveAndFlush(application);
        recordHistory(application, from, actor, reason, now);
        log.info("Application status changed applicationId={} {} -> {}", application.getApplicationId(), from, to);
    }

    // 현재 시각 (DB와 같은 마이크로초 정밀도)
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private void recordHistory(Application application, ApplicationStatus from, AuthPrincipal actor, String reason,
            LocalDateTime now) {
        historyRepository.save(ApplicationStatusHistory.builder()
                .applicationId(application.getId())
                .fromStatus(from)
                .toStatus(application.getStatus())
                .actorType(actor.role() == Role.ADMIN ? ActorType.ADMIN : ActorType.SERVICE)
                .actorId(actor.actorId())
                .reason(reason)
                .traceId(TraceIds.current())
                .createdAt(now)
                .build());
    }

    private Application find(String applicationId) {
        return applicationRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    // 신청자 본인 신청만 (applicantRef가 다르면 404)
    private Application findOwned(String applicationId, String applicantRef) {
        return applicationRepository.findByApplicationId(applicationId)
                .filter(a -> a.getApplicantRef().equals(applicantRef))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private AdminDetail detail(Application application) {
        return AdminDetail.of(application,
                historyRepository.findByApplicationIdOrderByCreatedAtAscIdAsc(application.getId()));
    }
}
