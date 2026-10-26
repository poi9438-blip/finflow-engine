-- 신청 접수 멱등성 (업무 명세 7장): HomeFin이 보낸 Idempotency-Key와 요청 값의 해시

-- 키 (2단계에서 키 없이 저장된 신청은 NULL)
ALTER TABLE applications ADD COLUMN idempotency_key VARCHAR(64);

-- 요청 값의 SHA-256 (64자 16진수): 같은 키로 다른 요청이 오면 409
ALTER TABLE applications ADD COLUMN request_hash VARCHAR(64);

-- 같은 키는 한 번만 저장 (동시에 들어와도 두 번째 저장은 실패), NULL은 여러 건 가능
ALTER TABLE applications ADD CONSTRAINT uk_applications_idempotency_key UNIQUE (idempotency_key);
