-- RiskWatch 심사 판정 (업무 명세 13장): 판정 입력 2개와 심사 시작 때 받은 판정

-- 판정 입력 (이 버전 전에 접수된 신청은 NULL → 판정하지 않음)
ALTER TABLE applications ADD COLUMN house_ownership VARCHAR(10);
ALTER TABLE applications ADD COLUMN dual_income BOOLEAN;

-- 판정 결과: EVALUATED / UNAVAILABLE / INPUT_MISSING (심사를 시작하지 않은 신청은 NULL)
ALTER TABLE applications ADD COLUMN risk_status VARCHAR(20);
-- RiskWatch 평가 번호 (UUID), 판정, 기준 버전, 판정 시각
ALTER TABLE applications ADD COLUMN risk_evaluation_id VARCHAR(36);
ALTER TABLE applications ADD COLUMN risk_verdict VARCHAR(20);
ALTER TABLE applications ADD COLUMN risk_criteria_version VARCHAR(30);
ALTER TABLE applications ADD COLUMN risk_evaluated_at DATETIME(6);
-- 판정을 받지 못한 이유 (짧은 값만, RiskWatch 응답 본문은 저장하지 않음)
ALTER TABLE applications ADD COLUMN risk_failure VARCHAR(50);
