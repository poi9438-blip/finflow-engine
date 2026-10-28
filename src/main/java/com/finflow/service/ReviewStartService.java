package com.finflow.service;

import com.finflow.common.TraceIds;
import com.finflow.dto.application.AdminDetail;
import com.finflow.integration.RiskResult;
import com.finflow.integration.RiskWatchClient;
import com.finflow.metrics.ApplicationMetrics;
import com.finflow.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 심사 시작 + RiskWatch 판정 (업무 명세 13장)
// 이 클래스에는 @Transactional을 두지 않음: RiskWatch 응답을 기다리는 동안 DB 연결·잠금을 잡지 않게
// (같은 클래스 안에서 부르면 트랜잭션이 적용되지 않으므로 ①·③은 ApplicationService에 둠)
@Service
@RequiredArgsConstructor
public class ReviewStartService {

    private final ApplicationService applicationService;
    private final RiskWatchClient riskWatchClient;
    private final ApplicationMetrics metrics;

    public AdminDetail startReview(String applicationId, AuthPrincipal actor) {
        // ① 확인 (읽기 전용 트랜잭션, 시작할 수 없으면 여기서 409)
        RiskResult risk = applicationService.prepareReview(applicationId)
                // ② RiskWatch 호출 (트랜잭션 없음, 실패해도 예외 없이 UNAVAILABLE)
                .map(request -> riskWatchClient.evaluate(request, TraceIds.current()))
                .orElseGet(RiskResult::inputMissing);
        // 업무 지표: RiskWatch 결과 (판정 / 응답 없음 / 입력 모자람)
        metrics.riskEvaluation(risk.status());
        // ③ 전이 + 판정 저장 (쓰기 트랜잭션)
        return applicationService.startReview(applicationId, risk, actor);
    }
}
