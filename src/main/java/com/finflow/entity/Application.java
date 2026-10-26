package com.finflow.entity;

import com.finflow.domain.ApplicationStatus;
import com.finflow.domain.ApplicationType;
import com.finflow.domain.ProductCode;
import com.finflow.util.EncryptedDecimalConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 정책대출 신청 (업무 명세 3장·9장), enum 칸은 VARCHAR로 저장
@Entity
@Table(name = "applications")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 외부에 보이는 신청 번호 (UUID)
    @Column(name = "application_id", nullable = false, updatable = false)
    private String applicationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private ApplicationType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "product_code", nullable = false)
    private ProductCode productCode;

    @Column(name = "applicant_ref", nullable = false)
    private String applicantRef;

    @Column(name = "requested_amount", nullable = false)
    private Long requestedAmount;

    @Column(name = "term_months", nullable = false)
    private Integer termMonths;

    @Column(name = "house_price", nullable = false)
    private Long housePrice;

    // 암호문으로 저장 (읽을 때 복호화)
    @Convert(converter = EncryptedDecimalConverter.class)
    @Column(name = "household_annual_income", nullable = false)
    private BigDecimal householdAnnualIncome;

    @Convert(converter = EncryptedDecimalConverter.class)
    @Column(nullable = false)
    private BigDecimal dti;

    @Column(name = "first_home", nullable = false)
    private Boolean firstHome;

    @Column(nullable = false)
    private Boolean newlywed;

    @Column(name = "newborn_within_2_years", nullable = false)
    private Boolean newbornWithin2Years;

    @Column(name = "child_count", nullable = false)
    private Integer childCount;

    private String region;

    @Column(name = "simulation_ref")
    private Long simulationRef;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "decision_note")
    private String decisionNote;

    // 낙관적 잠금 버전 (동시 수정 시 나중 요청 실패)
    @Version
    private Long version;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // 접수 요청의 traceId
    @Column(name = "trace_id", updatable = false)
    private String traceId;

    // HomeFin이 보낸 Idempotency-Key (2단계 신청은 null)
    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    // 요청 값의 SHA-256 (같은 키로 다른 요청이 왔는지 확인)
    @Column(name = "request_hash", updatable = false)
    private String requestHash;

    // 상태 변경 (전이 규칙 확인은 ApplicationService가 함). 승인·거절이면 결정 시각을 남김
    public void changeStatus(ApplicationStatus to, LocalDateTime now) {
        this.status = to;
        this.updatedAt = now;
        if (to == ApplicationStatus.APPROVED || to == ApplicationStatus.REJECTED) {
            this.decidedAt = now;
        }
    }

    public void recordRejectReason(String reason) {
        this.rejectReason = reason;
    }

    public void recordDecisionNote(String note) {
        this.decisionNote = note;
    }
}
