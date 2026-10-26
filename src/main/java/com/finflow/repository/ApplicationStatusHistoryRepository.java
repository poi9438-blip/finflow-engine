package com.finflow.repository;

import com.finflow.entity.ApplicationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 신청 상태 이력 저장/조회
public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {

    // 신청의 이력 (시간순)
    List<ApplicationStatusHistory> findByApplicationIdOrderByCreatedAtAscIdAsc(Long applicationId);
}
