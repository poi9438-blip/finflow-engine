package com.finflow.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.finflow.domain.ApplicationStatus.APPROVED;
import static com.finflow.domain.ApplicationStatus.CANCELLED;
import static com.finflow.domain.ApplicationStatus.EXPIRED;
import static com.finflow.domain.ApplicationStatus.RECEIVED;
import static com.finflow.domain.ApplicationStatus.REJECTED;
import static com.finflow.domain.ApplicationStatus.REVIEWING;
import static org.assertj.core.api.Assertions.assertThat;

// 상태 전이 규칙 단위 테스트 (업무 명세 4장 표 전체)
class ApplicationStatusTest {

    @Test
    @DisplayName("[TC-APP-201] 전이 규칙: 현재 상태 7가지(없음 포함) × 다음 상태 6가지 중 명세 4장 표의 7개만 허용")
    void onlySpecTransitionsAllowed() {
        // 명세 4장 표 (현재 → 다음)
        Set<String> spec = Set.of("null->RECEIVED", "RECEIVED->REVIEWING", "RECEIVED->CANCELLED",
                "RECEIVED->EXPIRED", "REVIEWING->APPROVED", "REVIEWING->REJECTED", "REVIEWING->CANCELLED");

        List<ApplicationStatus> froms = new ArrayList<>(Arrays.asList(ApplicationStatus.values()));
        froms.add(0, null);
        List<String> allowed = new ArrayList<>();
        for (ApplicationStatus from : froms) {
            for (ApplicationStatus to : ApplicationStatus.values()) {
                if (ApplicationStatus.canTransition(from, to)) {
                    allowed.add(from + "->" + to);
                }
            }
        }
        assertThat(allowed).containsExactlyInAnyOrderElementsOf(spec);

        // 진행 중 = 접수·심사 중
        assertThat(Arrays.stream(ApplicationStatus.values()).filter(ApplicationStatus::isActive))
                .containsExactlyInAnyOrder(RECEIVED, REVIEWING);
        assertThat(List.of(APPROVED, REJECTED, CANCELLED, EXPIRED)).noneMatch(ApplicationStatus::isActive);
    }
}
