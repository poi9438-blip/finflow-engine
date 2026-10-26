package com.finflow.security;

// 호출 주체의 역할 (업무 명세 2장). 두 역할은 섞이지 않음
public enum Role {
    // HomeFin 서버 (서비스 토큰)
    SERVICE,
    // 운영자 (운영자 토큰)
    ADMIN
}
