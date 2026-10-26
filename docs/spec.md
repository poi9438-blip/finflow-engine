# FinFlow 업무 명세

FinFlow가 **무엇을 하고, 누가 무엇을 할 수 있는지** 정한 문서입니다. 코드와 테스트 명세(`docs/test-cases`)는 이 문서를 기준으로 합니다.
이 문서와 코드가 다르면 둘 중 하나를 고치고, 바꾼 이유를 PR에 적습니다.

- 작성: 2026-10-26 (2단계 전)
- 기준값(만료 30일 등)은 **학습용 기본값**입니다. 실제 기관의 업무 기준이 아닙니다.

## 1. 범위

| 하는 것                                                | 하지 않는 것 (추후 추가 또는 범위 밖)                   |
| ------------------------------------------------------ | ------------------------------------------------------- |
| 정책대출 신청 접수 (HomeFin 서버가 보냄)               | 주택보증 신청 (종류 칸만 두고 상품은 나중에)            |
| 신청 상태 관리: 접수 → 심사 중 → 승인/거절, 취소, 만료 | 서류 보완 요청, 대출 실행·상환 같은 사후관리            |
| 상태가 바뀔 때마다 감사 기록 (누가, 언제, 왜, traceId) | 심사 판정 자체 (RiskWatch 단계에서 규칙 엔진으로)       |
| 같은 신청이 두 번 들어와도 한 번만 저장 (멱등성)       | Kafka 이벤트 발행 (Outbox → Kafka는 추후)               |
| 배치: 오래 대기한 신청 만료, 일별 집계                 | 사용자 화면 (사용자는 HomeFin 화면만 씀)                |
| 운영자 계정과 로그인                                   | 운영자 가입·비밀번호 변경 API (첫 계정은 `.env`로 만듦) |

## 2. 호출 주체와 인증

| 주체         | 인증                                                               | 할 수 있는 것                                    |
| ------------ | ------------------------------------------------------------------ | ------------------------------------------------ |
| HomeFin 서버 | **서비스 토큰**: `Authorization: Bearer <JWT>`, 역할 `SERVICE`     | 신청 접수, 신청자 본인 신청 조회, 신청 취소      |
| 운영자       | **운영자 토큰**: `POST /api/auth/login`으로 받은 JWT, 역할 `ADMIN` | 신청 목록·상세 조회, 심사 시작, 승인, 거절, 배치 |
| 배치         | 서버 안에서 실행 (토큰 없음)                                       | 만료 처리, 일별 집계                             |

- **서비스 토큰:** HomeFin이 요청마다 만듭니다.
  - HS256, 비밀키 `SERVICE_TOKEN_SECRET`(HomeFin과 FinFlow만 앎, 사용자 로그인용 JWT 비밀키와 다른 값)
  - `iss=homefin-mate`, `aud=finflow`, 유효 기간 5분 이하. 셋 중 하나라도 맞지 않으면 401
- **운영자 토큰:**
  - HS256, 비밀키 `OPERATOR_JWT_SECRET`(FinFlow만 앎), 유효 기간 기본 8시간
  - 토큰의 운영자가 DB에 없으면 401 (HomeFin TC-AUTH-115와 같은 방식)
- **역할은 섞이지 않습니다.** 서비스 토큰으로 운영자 API를 부르거나, 운영자 토큰으로 신청을 접수하면 403입니다.
- **운영자 계정:** 비밀번호는 BCrypt로 저장합니다. 서버가 시작될 때 `.env`의 `OPERATOR_EMAIL`·`OPERATOR_PASSWORD`로 첫 계정을 만들고(이미 있으면 그대로, 비우면 만들지 않음), 비밀번호는 기본값이 없습니다.
- **로그인 시도 제한:** 같은 이메일의 최근 15분 안 실패가 5번 이상이면 맞는 비밀번호여도 429 (HomeFin과 같은 기준). 실패 기록은 이메일 HMAC 값으로만 남기고, 없는 이메일도 비밀번호 확인을 한 번 해서 응답 시간으로 계정이 있는지 알 수 없게 합니다.

## 3. 신청

### 3.1 입력 항목 (`POST /api/applications`)

| 항목                    | 필수   | 규칙                                             | 비고                               |
| ----------------------- | ------ | ------------------------------------------------ | ---------------------------------- |
| `productCode`           | 예     | `DIDIMDOL` / `NEWBORN_DIDIMDOL` / `BOGEUMJARI`   | 종류(`type`)는 지금은 항상 `LOAN`  |
| `applicantRef`          | 예     | `homefin:` + 숫자 (HomeFin 사용자 id), 64자 이하 | 이메일·이름은 받지 않음            |
| `requestedAmount`       | 예     | 원 단위 정수, 1원 이상 10억 원 이하              |                                    |
| `termMonths`            | 예     | 12 이상 600 이하                                 | HomeFin 대출 계산과 같은 상한      |
| `housePrice`            | 예     | 원 단위 정수, 1원 이상                           |                                    |
| `householdAnnualIncome` | 예     | 원 단위 정수, 0 이상                             | 암호화 저장                        |
| `dti`                   | 예     | 0 이상 9.9999 이하, 소수 넷째 자리               | HomeFin 계산 결과, 암호화 저장     |
| `firstHome`             | 예     | true / false                                     |                                    |
| `newlywed`              | 예     | true / false                                     |                                    |
| `newbornWithin2Years`   | 예     | true / false                                     |                                    |
| `childCount`            | 예     | 0 이상 20 이하                                   |                                    |
| `region`                | 아니요 | 20자 이하                                        |                                    |
| `simulationRef`         | 아니요 | HomeFin 계산 기록 id (양의 정수)                 | 어떤 계산에서 나온 신청인지 추적용 |

- 형식이 틀리면 400 `필드: 메시지` (HomeFin과 같은 형식, 메시지는 영어).
- 시각은 DB와 같은 마이크로초까지 씁니다 (접수 응답의 `receivedAt`이 저장된 값·조회 응답과 같음).
- **상품 조건(소득 기준, 주택 가격 상한 등)은 FinFlow가 판정하지 않습니다.** 조건 판정은 HomeFin 추천과 RiskWatch 심사 규칙이 합니다.
- **같은 신청자가 같은 상품으로 진행 중(접수·심사 중)인 신청이 있으면 409** `Active application already exists`. 같은 신청이 동시에 들어와 둘 다 확인을 통과해도, DB의 unique 제약(`active_key`: 진행 중인 신청만 `신청자:상품` 값을 가짐)이 두 번째 저장을 막습니다.

### 3.2 신청 번호

- 외부에 보이는 번호는 `applicationId`(UUID)입니다. DB의 순번 id는 응답에 넣지 않습니다 (HomeFin #50: 순번으로 가입 순서·건수를 추측할 수 있음).

## 4. 상태와 전이

```
            심사 시작(ADMIN)          승인(ADMIN)
 RECEIVED ─────────────────▶ REVIEWING ─────────▶ APPROVED
   │  │                          │  │
   │  │ 취소(SERVICE)            │  └─ 거절(ADMIN, 사유 필수) ─▶ REJECTED
   │  └──────────────▶ CANCELLED ◀┘ 취소(SERVICE)
   │
   └─ 접수 후 30일 동안 심사 시작 안 됨(BATCH) ─▶ EXPIRED
```

| 현재 상태   | 다음 상태   | 누가    | 조건                                                    |
| ----------- | ----------- | ------- | ------------------------------------------------------- |
| (없음)      | `RECEIVED`  | SERVICE | 신청 접수                                               |
| `RECEIVED`  | `REVIEWING` | ADMIN   | 심사 시작                                               |
| `RECEIVED`  | `CANCELLED` | SERVICE | 신청자 취소                                             |
| `RECEIVED`  | `EXPIRED`   | BATCH   | 접수 시각 + `APPLICATION_EXPIRE_DAYS`(기본 30일)이 지남 |
| `REVIEWING` | `APPROVED`  | ADMIN   | 메모(선택, 500자 이하)                                  |
| `REVIEWING` | `REJECTED`  | ADMIN   | 거절 사유 필수 (1~500자)                                |
| `REVIEWING` | `CANCELLED` | SERVICE | 신청자 취소                                             |

- **표에 없는 전이는 모두 409** `Invalid status transition: <현재> -> <다음>`. 승인·거절·취소·만료는 끝 상태라 더 바뀌지 않습니다.
- **예외: 이미 취소된 신청을 다시 취소하면 200**으로 같은 결과를 돌려주고 기록은 남기지 않습니다. HomeFin이 응답을 못 받고 다시 보낸 경우를 위해서입니다. 승인·거절은 사람이 하는 일이라 반복하면 409입니다.
- **심사 중인 신청은 만료하지 않습니다.** 심사를 시작했으면 운영자가 결론을 내야 합니다.
- **동시에 두 요청이 상태를 바꾸면** 먼저 저장된 쪽만 반영되고 나중 쪽은 409입니다. 둘 다 같은 상태를 읽었으면 `Application was updated by another request`(버전 번호로 확인, 낙관적 잠금), 나중 쪽이 바뀐 상태를 읽었으면 `Invalid status transition`입니다.

## 5. 감사 기록

- 상태가 바뀔 때마다 **같은 트랜잭션에서** `application_status_history`에 한 줄을 남깁니다. 상태만 바뀌고 기록이 없거나, 기록만 있고 상태가 안 바뀐 경우가 없어야 합니다.
- 남기는 값: 이전 상태(접수는 없음), 다음 상태, 주체 종류(`SERVICE`/`ADMIN`/`BATCH`), 주체 id(`operator:{운영자 id}` / `homefin-mate` / 배치 이름), 사유·메모, traceId, 시각.
- 기록은 수정·삭제 API가 없습니다.
- 거부된 요청(잘못된 전이, 권한 없음)은 상태가 바뀌지 않으므로 기록하지 않고 WARN 로그만 남깁니다.

## 6. API

모든 응답은 공통 형식 `{status, message, data, traceId}`입니다. 시각은 시간대를 붙여 보냅니다 (`…Z`).

| 메서드 | 경로                                                          | 주체    | 내용                                                           | 단계 |
| ------ | ------------------------------------------------------------- | ------- | -------------------------------------------------------------- | ---- |
| POST   | `/api/auth/login`                                             | 누구나  | 운영자 로그인 → `{token, expiresAt}`                           | 2    |
| POST   | `/api/applications`                                           | SERVICE | 신청 접수 → 201 `{applicationId, status, receivedAt}`          | 2    |
| GET    | `/api/applications/{applicationId}?applicantRef=`             | SERVICE | 신청자 본인 신청 조회 (상태, 금액, 기간, 결정 시각, 거절 사유) | 2    |
| POST   | `/api/applications/{applicationId}/cancel`                    | SERVICE | 본문 `{applicantRef}`, 취소                                    | 2    |
| GET    | `/api/admin/applications?status=&productCode=&page=0&size=20` | ADMIN   | 목록 (최신순, size 최대 100)                                   | 2    |
| GET    | `/api/admin/applications/{applicationId}`                     | ADMIN   | 상세 (입력값 전체 + 상태 이력)                                 | 2    |
| POST   | `/api/admin/applications/{applicationId}/review`              | ADMIN   | 심사 시작                                                      | 2    |
| POST   | `/api/admin/applications/{applicationId}/approve`             | ADMIN   | 승인, 본문 `{note}` (선택)                                     | 2    |
| POST   | `/api/admin/applications/{applicationId}/reject`              | ADMIN   | 거절, 본문 `{reason}` (필수)                                   | 2    |
| POST   | `/api/admin/batch/expire`                                     | ADMIN   | 만료 배치를 지금 실행 (결과: 만료한 건수)                      | 4    |
| POST   | `/api/admin/batch/daily-stats?date=`                          | ADMIN   | 그날 집계를 지금 실행 (다시 실행해도 같은 결과)                | 4    |
| GET    | `/api/admin/stats/daily?from=&to=`                            | ADMIN   | 일별 집계 조회 (from ≤ to, 최대 92일)                          | 4    |

- **신청자 확인:** HomeFin 서버를 믿더라도, 조회·취소는 `applicantRef`가 신청의 값과 같아야 합니다. 다르면 404로 응답해 다른 사람 신청의 존재 여부를 알려 주지 않습니다.
- `/actuator/health`만 인증 없이 공개합니다 (1단계와 같음).

## 7. 멱등성 (3단계)

HomeFin이 응답을 못 받고 같은 신청을 다시 보내도 신청은 하나만 생겨야 합니다.

- `POST /api/applications`에 **`Idempotency-Key` 헤더 필수** (8~64자, 영문·숫자·`-`, UUID 권장). 없거나 형식이 틀리면 400.
- FinFlow는 키와 함께 **요청 값의 해시(SHA-256)**를 저장합니다. 본문 문자열이 아니라 검증한 값(3.1의 13개 항목)으로 만들어서, JSON 키 순서·공백·숫자 표기(DTI를 숫자 `0.3041`로 보내든 문자열 `"0.3041"`로 보내든)가 달라도 값이 같으면 같은 요청입니다. 소수 다섯째 자리 이상으로 쓴 DTI(`0.30410`)는 3.1의 입력 규칙에서 400입니다.
- **키를 먼저 확인합니다.** 재전송은 3.1의 진행 중 중복 규칙(409)보다 우선합니다. 그래야 응답을 못 받은 HomeFin이 다시 보냈을 때 409가 아니라 처음 결과를 받습니다.

| 상황                     | 응답                                                                                                                                                        |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 처음 보는 키             | 201, 새 신청                                                                                                                                                |
| 같은 키 + 같은 요청 값   | 200, **처음 응답과 같은 본문** (그 뒤 상태가 바뀌었어도 처음 응답 그대로, 현재 상태는 조회 API로), 새 신청·감사 기록 없음, 헤더 `Idempotent-Replayed: true` |
| 같은 키 + 다른 요청 값   | 409 `Idempotency key reused with different request`                                                                                                         |
| 같은 키로 동시에 두 요청 | 하나만 저장 (키에 unique 제약), 나머지는 저장이 실패한 뒤 키로 다시 조회해서 200 재전송                                                                     |

- 키는 신청과 함께 계속 보관합니다 (만료 없음). 2단계에서 키 없이 저장된 신청은 키 칸이 비어 있습니다.
- 멱등성(같은 요청의 재전송)과 3.1의 중복 신청 규칙(다른 요청이지만 같은 신청자·상품)은 다른 규칙입니다. 둘 다 확인합니다.

## 8. 배치 (4단계)

| 배치      | 언제                                   | 하는 일                                                                                                 |
| --------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| 만료      | 매일 03:00 (한국 시각), 수동 실행 가능 | `RECEIVED`이고 접수 후 `APPLICATION_EXPIRE_DAYS`(기본 30일)가 지난 신청을 `EXPIRED`로, 건마다 감사 기록 |
| 일별 집계 | 매일 03:30 (한국 시각), 수동 실행 가능 | 전날(한국 날짜) 상품별 접수·승인·거절·취소·만료 건수를 `daily_application_stats`에 저장                 |

- **다시 실행해도 결과가 같아야 합니다.** 만료는 이미 만료된 신청을 건너뛰고, 집계는 같은 날짜 값을 덮어씁니다.
- 한 건이 실패해도 나머지는 처리하고, 실패 건수와 traceId(`batch-` 접두사)를 로그에 남깁니다.
- 날짜 기준: 서버 시각은 UTC로 저장하고, "하루"는 한국 날짜(Asia/Seoul)로 나눕니다.
- **구현: Spring Batch** (4단계에서 결정). 실행 기록·재시작은 Spring Batch 메타 테이블(Flyway V3)이 남깁니다.
- **만료 배치:** 대상을 id 순서로 이어 읽습니다(페이지 번호로 읽으면 앞 페이지를 처리하는 동안 대상에서 빠진 행 때문에 뒤 페이지가 당겨져 건너뛰는 행이 생김). 처리 직전에 다시 확인해서, 그사이 운영자가 심사를 시작했으면 만료하지 않습니다. 동시 수정으로 실패한 건은 건너뛰고(결과의 건너뛴 건수), 다른 오류는 배치를 실패로 끝냅니다(다시 실행 가능).
- **기준 시각:** 접수 시각 + 만료 기간 ≤ 배치 실행 시각이면 만료합니다. 감사 기록의 주체는 `BATCH` / `applicationExpireJob`, traceId는 실행마다 `batch-UUID`입니다.
- **일별 집계:** 접수 건수는 신청의 접수 시각으로, 승인·거절·취소·만료 건수는 그날 생긴 상태 이력으로 셉니다. 상품 3개 모두 한 줄씩(0 포함) 저장하고, 같은 날짜를 다시 실행하면 그 날짜 줄을 지우고 새로 씁니다(한 트랜잭션).
- **다시 실행:** Spring Batch는 같은 파라미터로 완료된 실행을 다시 하지 않으므로, 실행 시각을 파라미터에 넣어 매번 새로 실행합니다(같은 날짜 집계를 다시 할 때 필요).
- **설정:** `APPLICATION_EXPIRE_DAYS`(만료 기간, 기본 30), `BATCH_SCHEDULING_ENABLED`(자동 실행, 기본 켜짐), `BATCH_CHUNK_SIZE`(한 번에 처리하는 건수, 기본 100).
- **서버 한 대 기준입니다.** 서버를 여러 대 띄우면 자동 실행이 겹칠 수 있습니다(분산 잠금은 범위 밖).

## 9. 데이터 모델

| 표                           | 주요 칸                                                                                                                                                                                                                                                                                                                                        | 단계 |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---- |
| `applications`               | id, application_id(UUID, unique), type, product_code, applicant_ref, requested_amount, term_months, house_price, household_annual_income(암호문), dti(암호문), first_home, newlywed, newborn_within_2_years, child_count, region, simulation_ref, status, reject_reason, decision_note, version, received_at, decided_at, updated_at, trace_id | 2    |
| `application_status_history` | id, application_id(FK), from_status, to_status, actor_type, actor_id, reason, trace_id, created_at                                                                                                                                                                                                                                             | 2    |
| `operators`                  | id, email(unique), password_hash, created_at                                                                                                                                                                                                                                                                                                   | 2    |
| `operator_login_failures`    | id, email_hash(HMAC), created_at                                                                                                                                                                                                                                                                                                               | 2    |
| `applications` 칸 추가       | idempotency_key(unique), request_hash                                                                                                                                                                                                                                                                                                          | 3    |
| `daily_application_stats`    | stat_date, product_code, received, approved, rejected, cancelled, expired (stat_date+product_code unique)                                                                                                                                                                                                                                      | 4    |

- 상태·상품·주체 종류 같은 enum 칸은 MySQL ENUM이 아니라 VARCHAR로 저장합니다 (상태를 추가할 때 표를 바꾸지 않도록).
- 진행 중 중복 방지: `applications.active_key`(생성 칼럼, 접수·심사 중일 때만 `신청자:상품`) + unique.
- 인덱스: `applications(applicant_ref, product_code, status)`(중복 신청 확인), `applications(status, received_at)`(만료 배치·목록), `application_status_history(application_id, created_at)`.
- 테이블은 Flyway(V1~)가 만들고, Hibernate는 확인만 합니다.

## 10. 개인정보·보안

- 신청자는 `applicantRef`(HomeFin 사용자 id)로만 구분합니다. 이메일·이름·연락처를 받지 않습니다.
- 연소득과 DTI는 AES-256-GCM으로 암호화해 저장합니다 (키 `ENCRYPTION_KEY`, 기본값 없음). 목록 API에는 넣지 않고 운영자 상세에서만 보여 줍니다.
- 로그에는 금액·소득·DTI를 남기지 않고 applicationId와 상태만 남깁니다.
- 로그인 실패 기록의 이메일은 HMAC 값으로 저장합니다 (HomeFin #33과 같은 방식).
- 비밀값(`SERVICE_TOKEN_SECRET`, `OPERATOR_JWT_SECRET`, `ENCRYPTION_KEY`, `OPERATOR_PASSWORD`, DB 비밀번호)은 모두 기본값 없이 `.env`에서만 받습니다.

## 11. 오류 응답

| 상태 | 언제                                                                                   |
| ---- | -------------------------------------------------------------------------------------- |
| 400  | 입력 형식, 페이지 범위, `Idempotency-Key` 없음·형식 오류                               |
| 401  | 토큰 없음·만료·서명 오류, 서비스 토큰의 `iss`/`aud` 불일치, 삭제된 운영자, 로그인 실패 |
| 403  | 역할이 맞지 않음 (서비스 ↔ 운영자)                                                     |
| 404  | 신청이 없음, 또는 `applicantRef`가 다름                                                |
| 409  | 잘못된 상태 전이, 진행 중인 같은 신청, 멱등성 키 재사용, 동시 수정                     |
| 429  | 운영자 로그인 시도 제한                                                                |
| 500  | 예상 못 한 오류 (일반 메시지만, 원인은 서버 로그)                                      |

## 12. 단계별 범위

| 단계 | 이 문서의 범위                                                                       | 테스트 명세                                                                |
| ---- | ------------------------------------------------------------------------------------ | -------------------------------------------------------------------------- |
| 1    | 공통 응답·오류, traceId, 보안 기본값 (완료)                                          | [base.md](test-cases/base.md), [api-errors.md](test-cases/api-errors.md)   |
| 2    | 2장 인증, 3장 신청, 4장 상태, 5장 감사, 6장의 2단계 API, 9장의 2단계 표, 10장 (완료) | [application.md](test-cases/application.md), [auth.md](test-cases/auth.md) |
| 3    | 7장 멱등성                                                                           | [idempotency.md](test-cases/idempotency.md)                                |
| 4    | 8장 배치, 6장의 4단계 API                                                            | [batch.md](test-cases/batch.md)                                            |
| 이후 | RiskWatch 연동 (심사 시작 때 규칙 엔진 판정과 근거 저장), HomeFin 화면의 신청 버튼   | RiskWatch 단계에서 작성                                                    |
