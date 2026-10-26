package com.finflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.common.ApiResponse;
import com.finflow.common.TraceIds;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

// 보안 설정: 세션 미사용, health만 공개, 401/403 JSON 응답(traceId 포함)
@Configuration
@EnableWebSecurity
public class SecurityConfig {
        // 주소별 규칙: health만 공개, 나머지는 인증 필요
        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
                return http
                                // 쿠키 인증이 없어 CSRF 확인 끔 (호출은 모두 Authorization 헤더)
                                .csrf(AbstractHttpConfigurer::disable)
                                .httpBasic(AbstractHttpConfigurer::disable)
                                .formLogin(AbstractHttpConfigurer::disable)
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers("/actuator/health").permitAll()
                                                .anyRequest().authenticated())
                                .exceptionHandling(ex -> ex
                                                // 401: 누구인지 모름 = 인증 실패
                                                .authenticationEntryPoint((req, res, e) -> writeError(res, objectMapper,
                                                                HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                                                // 403: 권한이 없음 = 인가 실패
                                                .accessDeniedHandler((req, res, e) -> writeError(res, objectMapper,
                                                                HttpServletResponse.SC_FORBIDDEN, "Access denied")))
                                .build();
        }

        // 보안 오류 응답 (공통 형식 + traceId)
        private static void writeError(HttpServletResponse res, ObjectMapper objectMapper, int status, String message)
                        throws IOException {
                res.setStatus(status);
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(res.getWriter(), ApiResponse.error(message, TraceIds.current()));
        }

        // traceId 필터를 모든 필터보다 먼저 실행
        @Bean
        public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
                FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
                registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
                return registration;
        }
}
