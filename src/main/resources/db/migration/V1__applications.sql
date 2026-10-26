-- 신청, 신청 상태 이력, 운영자, 운영자 로그인 실패 (업무 명세 9장)

-- 정책대출 신청
CREATE TABLE applications (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    -- 외부에 보이는 신청 번호 (UUID, DB 순번 id는 응답에 넣지 않음)
    application_id          VARCHAR(36)  NOT NULL,
    type                    VARCHAR(20)  NOT NULL,
    product_code            VARCHAR(30)  NOT NULL,
    -- 신청자: HomeFin 사용자 id (homefin:{id}), 이메일·이름은 저장하지 않음
    applicant_ref           VARCHAR(64)  NOT NULL,
    requested_amount        BIGINT       NOT NULL,
    term_months             INT          NOT NULL,
    house_price             BIGINT       NOT NULL,
    -- 연소득·DTI는 AES-256-GCM 암호문 (Base64)
    household_annual_income VARCHAR(255) NOT NULL,
    dti                     VARCHAR(255) NOT NULL,
    first_home              BOOLEAN      NOT NULL,
    newlywed                BOOLEAN      NOT NULL,
    newborn_within_2_years  BOOLEAN      NOT NULL,
    child_count             INT          NOT NULL,
    region                  VARCHAR(20),
    simulation_ref          BIGINT,
    status                  VARCHAR(20)  NOT NULL,
    reject_reason           VARCHAR(500),
    decision_note           VARCHAR(500),
    -- 낙관적 잠금 버전 (동시에 상태를 바꾸면 나중 요청은 실패)
    version                 BIGINT       NOT NULL DEFAULT 0,
    received_at             DATETIME(6)  NOT NULL,
    decided_at              DATETIME(6),
    updated_at              DATETIME(6)  NOT NULL,
    trace_id                VARCHAR(64),
    -- 진행 중(접수·심사 중)인 신청만 "신청자:상품" 값을 가짐 → unique로 진행 중 중복 신청을 DB에서도 막음 (끝난 신청은 NULL이라 여러 개 가능)
    active_key              VARCHAR(100) GENERATED ALWAYS AS (
                                CASE WHEN status IN ('RECEIVED', 'REVIEWING')
                                     THEN CONCAT(applicant_ref, ':', product_code) END),
    PRIMARY KEY (id),
    CONSTRAINT uk_applications_application_id UNIQUE (application_id),
    CONSTRAINT uk_applications_active_key UNIQUE (active_key)
);

-- 운영자 목록 (상태별 최신순)·만료 배치
CREATE INDEX idx_applications_status_received ON applications (status, received_at);
-- 신청자 본인 신청 확인
CREATE INDEX idx_applications_applicant ON applications (applicant_ref, product_code, status);

-- 신청 상태 이력 (감사 기록, 상태 변경과 같은 트랜잭션에서 저장, 수정·삭제 API 없음)
CREATE TABLE application_status_history (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    application_id  BIGINT       NOT NULL,
    -- 접수 줄은 이전 상태 없음
    from_status     VARCHAR(20),
    to_status       VARCHAR(20)  NOT NULL,
    -- SERVICE / ADMIN / BATCH
    actor_type      VARCHAR(20)  NOT NULL,
    -- homefin-mate / operator:{id} / 배치 이름
    actor_id        VARCHAR(64)  NOT NULL,
    reason          VARCHAR(500),
    trace_id        VARCHAR(64),
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_history_application FOREIGN KEY (application_id) REFERENCES applications (id)
);

CREATE INDEX idx_history_application_created ON application_status_history (application_id, created_at);

-- 운영자 계정 (비밀번호는 BCrypt 해시)
CREATE TABLE operators (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    email          VARCHAR(100) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_operators_email UNIQUE (email)
);

-- 운영자 로그인 실패 (시도 제한용, 이메일은 HMAC 값만)
CREATE TABLE operator_login_failures (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    email_hash  VARCHAR(80)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_login_failures_email_created ON operator_login_failures (email_hash, created_at);
