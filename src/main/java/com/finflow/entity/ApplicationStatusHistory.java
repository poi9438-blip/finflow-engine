package com.finflow.entity;

import com.finflow.domain.ActorType;
import com.finflow.domain.ApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

// 신청 상태 이력 = 감사 기록 (누가, 언제, 왜, traceId, 업무 명세 5장). 저장 후 바뀌지 않음
@Entity
@Table(name = "application_status_history")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ApplicationStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 신청의 DB id (외래키)
    @Column(name = "application_id", nullable = false, updatable = false)
    private Long applicationId;

    // 접수 줄은 null
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "from_status", updatable = false)
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "to_status", nullable = false, updatable = false)
    private ApplicationStatus toStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private ActorType actorType;

    // homefin-mate / operator:{id} / 배치 이름
    @Column(name = "actor_id", nullable = false, updatable = false)
    private String actorId;

    // 거절 사유, 승인 메모
    @Column(updatable = false)
    private String reason;

    @Column(name = "trace_id", updatable = false)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
