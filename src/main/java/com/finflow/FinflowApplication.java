package com.finflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

import java.util.TimeZone;

// FinFlow 시작점 (대출·보증 신청 처리 서비스), 자동 생성 로그인 계정 끔
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class FinflowApplication {
    public static void main(String[] args) {
        // 서버 시각 기준을 UTC로 고정 (신청 기록·배치 기준 시각이 PC 시간대에 따라 바뀌지 않도록, HomeFin과 같음)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(FinflowApplication.class, args);
    }
}
