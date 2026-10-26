package com.finflow.repository;

import com.finflow.entity.Operator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// 운영자 계정 저장/조회
public interface OperatorRepository extends JpaRepository<Operator, Long> {

    Optional<Operator> findByEmail(String email);
}
