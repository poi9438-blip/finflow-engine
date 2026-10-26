package com.finflow.security;

import com.finflow.repository.OperatorRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

// Bearer 토큰 → 서비스(SERVICE) 또는 운영자(ADMIN)로 인증, 둘 다 아니면 인증하지 않음(→ 401)
@RequiredArgsConstructor
public class AuthTokenFilter extends OncePerRequestFilter {

    private final ServiceTokenVerifier serviceTokenVerifier;
    private final OperatorTokens operatorTokens;
    private final OperatorRepository operatorRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            authenticate(header.substring(7)).ifPresent(principal -> {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }

    private Optional<AuthPrincipal> authenticate(String token) {
        if (serviceTokenVerifier.isValid(token)) {
            return Optional.of(AuthPrincipal.service());
        }
        return operatorTokens.operatorId(token)
                .filter(operatorRepository::existsById)
                .map(AuthPrincipal::operator);
    }
}
