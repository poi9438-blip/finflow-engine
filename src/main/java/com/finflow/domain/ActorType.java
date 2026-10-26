package com.finflow.domain;

// 상태를 바꾼 주체 종류 (감사 기록)
public enum ActorType {
    // HomeFin 서버 (서비스 토큰)
    SERVICE,
    // 운영자 (운영자 토큰)
    ADMIN,
    // 서버 안의 배치
    BATCH
}
