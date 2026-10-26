package com.finflow.security;

// 인증된 호출 주체 (역할, 감사 기록에 남길 주체 id: homefin-mate / operator:{id})
public record AuthPrincipal(Role role, String actorId) {

    // HomeFin 서버의 주체 id
    public static final String HOMEFIN = "homefin-mate";

    public static AuthPrincipal service() {
        return new AuthPrincipal(Role.SERVICE, HOMEFIN);
    }

    public static AuthPrincipal operator(Long operatorId) {
        return new AuthPrincipal(Role.ADMIN, "operator:" + operatorId);
    }
}
