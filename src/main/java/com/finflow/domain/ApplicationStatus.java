package com.finflow.domain;

import java.util.Map;
import java.util.Set;

// 신청 상태와 허용되는 전이
public enum ApplicationStatus {
    RECEIVED,
    REVIEWING,
    APPROVED,
    REJECTED,
    CANCELLED,
    EXPIRED;

    // 현재 상태 → 갈 수 있는 다음 상태 (표에 없는 상태는 끝 상태)
    private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED = Map.of(
            RECEIVED, Set.of(REVIEWING, CANCELLED, EXPIRED),
            REVIEWING, Set.of(APPROVED, REJECTED, CANCELLED));

    // 진행 중(접수·심사 중): 같은 신청자·상품의 새 신청을 막고, 신청자가 취소할 수 있음
    public boolean isActive() {
        return this == RECEIVED || this == REVIEWING;
    }

    // from이 null이면 접수 (RECEIVED만 가능)
    public static boolean canTransition(ApplicationStatus from, ApplicationStatus to) {
        if (from == null) {
            return to == RECEIVED;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }
}
