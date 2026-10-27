package com.finflow.domain;

// 심사 시작 때 받은 RiskWatch 판정의 상태 (업무 명세 13장)
public enum RiskStatus {
    // 판정을 받음
    EVALUATED,
    // 연결 실패·시간 초과·오류 응답 (심사는 시작)
    UNAVAILABLE,
    // 판정 입력(주택 보유·맞벌이)이 없는 예전 신청이라 부르지 않음
    INPUT_MISSING
}
