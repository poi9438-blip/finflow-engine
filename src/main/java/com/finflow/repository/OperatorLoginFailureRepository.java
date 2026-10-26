package com.finflow.repository;

import com.finflow.entity.OperatorLoginFailure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

// 운영자 로그인 실패 저장/집계
public interface OperatorLoginFailureRepository extends JpaRepository<OperatorLoginFailure, Long> {

    // 기준 시각 이후 실패 수 (시도 제한)
    long countByEmailHashAndCreatedAtAfter(String emailHash, LocalDateTime after);
}
