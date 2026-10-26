package com.finflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.common.ApiResponse;
import com.finflow.common.TraceIds;
import com.finflow.repository.OperatorRepository;
import com.finflow.security.AuthTokenFilter;
import com.finflow.security.OperatorTokens;
import com.finflow.security.Role;
import com.finflow.security.ServiceTokenVerifier;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

// 보안 설정: 세션 미사용, 주소별 역할(SERVICE / ADMIN), 401/403 JSON 응답(traceId 포함)
@Configuration
@EnableWebSecurity
public class SecurityConfig {
        // 주소별 규칙: health·운영자 로그인 공개, 신청 API는 SERVICE, 운영자 API는 ADMIN, 나머지는 인증 필요
        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                        ServiceTokenVerifier serviceTokenVerifier, OperatorTokens operatorTokens,
                        OperatorRepository operatorRepository) throws Exception {
                return http
                                // 쿠키 인증이 없어 CSRF 확인 끔 (호출은 모두 Authorization 헤더)
                                .csrf(AbstractHttpConfigurer::disable)
                                .httpBasic(AbstractHttpConfigurer::disable)
                                .formLogin(AbstractHttpConfigurer::disable)
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers("/actuator/health").permitAll()
                                                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                                                .requestMatchers("/api/applications", "/api/applications/**")
                                                .hasRole(Role.SERVICE.name())
                                                .requestMatchers("/api/admin/**").hasRole(Role.ADMIN.name())
                                                .anyRequest().authenticated())
                                // Bearer 토큰 확인 필터를 기본 로그인 필터 앞에 추가
                                .addFilterBefore(new AuthTokenFilter(serviceTokenVerifier, operatorTokens,
                                                operatorRepository), UsernamePasswordAuthenticationFilter.class)
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

        // 운영자 비밀번호 해시 (BCrypt)
        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        // traceId 필터를 모든 필터보다 먼저 실행
        @Bean
        public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
                FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
                registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
                return registration;
        }
}
