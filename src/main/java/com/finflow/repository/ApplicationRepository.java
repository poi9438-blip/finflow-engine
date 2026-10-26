package com.finflow.repository;

import com.finflow.domain.ApplicationStatus;
import com.finflow.domain.ProductCode;
import com.finflow.entity.Application;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 신청 저장/조회
public interface ApplicationRepository extends JpaRepository<Application, Long> {

    // 외부 신청 번호로 조회
    Optional<Application> findByApplicationId(String applicationId);

    // 같은 신청자·상품의 진행 중 신청이 있는지 (중복 신청 확인)
    boolean existsByApplicantRefAndProductCodeAndStatusIn(String applicantRef, ProductCode productCode,
            Collection<ApplicationStatus> statuses);

    // 운영자 목록: 상태·상품 필터(없으면 전체), 최신순 (같은 시각이면 id 역순)
    @Query("select a from Application a"
            + " where (:status is null or a.status = :status)"
            + " and (:productCode is null or a.productCode = :productCode)"
            + " order by a.receivedAt desc, a.id desc")
    List<Application> search(@Param("status") ApplicationStatus status,
            @Param("productCode") ProductCode productCode, Pageable pageable);
}
