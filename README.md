# FinFlow Engine

대출·보증 **신청을 접수하고 상태를 관리하는** 서비스입니다. HomeFin Mate(상담·계산)에서 신청이 들어오면 FinFlow가 신청을 저장하고, 심사 → 승인/거절 같은 상태 변화를 감사 기록과 함께 남깁니다.

직접 만든 FinSight 구성의 FinFlow(거래·정산 엔진)를 처음부터 다시 설계한 저장소입니다. 원본과 달라진 점은 [docs/known-issues.md](docs/known-issues.md)에 있습니다.

## 서비스 구성에서의 위치

```
사용자 ─▶ HomeFin Mate (:8100) ──X-Trace-Id──▶ FinFlow (:8110) ──▶ RiskWatch (:8120, 심사 규칙)
                                                   ▲
                                         운영자 (ADMIN JWT: 상태 변경·조회·배치)
```

- **신청 접수:** HomeFin 서버가 서비스 간 토큰으로 호출합니다. 사용자 브라우저는 FinFlow를 직접 부르지 않습니다.
- **traceId:** HomeFin이 보낸 `X-Trace-Id`를 그대로 씁니다. 운영자가 직접 부르는 등 헤더가 없으면 `ff-UUID`를 만듭니다.

## 진행 단계

| 단계 | 내용                                                         | 상태      |
| ---- | ------------------------------------------------------------ | --------- |
| 1    | 뼈대: 공통 응답·오류 형식, traceId, 보안 기본값, compose, CI | 진행 중   |
| 2    | 신청 상태 흐름 + 감사 기록, 서비스 토큰·운영자 로그인        | 명세 작성 |
| 3    | 멱등성 (같은 신청이 두 번 들어와도 한 번만 저장)             |           |
| 4    | 배치 (오래 대기한 신청 만료, 일별 집계)                      |           |

Kafka(Outbox → Kafka 이벤트)는 추후 추가 기능입니다.

## 실행

### 0. 환경변수

```bash
cp .env.example .env   # 비밀번호를 직접 정한 값으로 바꾸기
```

### 1. Docker Compose

```bash
docker compose up -d --build
curl http://localhost:8110/actuator/health   # {"status":"UP"}
```

| 항목  | 주소                  |
| ----- | --------------------- |
| 서버  | http://localhost:8110 |
| MySQL | 127.0.0.1:3311        |

### 2. 테스트

```bash
./mvnw test                   # Windows: mvnw.cmd test
python scripts/tc_status.py   # 테스트 명세와 결과 대조
```

## 문서

- 업무 명세 (무엇을 하는지, 상태·API·데이터): [docs/spec.md](docs/spec.md)
- 테스트 기준·명세: [docs/TESTING.md](docs/TESTING.md), [docs/test-cases/](docs/test-cases/README.md)
- 원본에서 바꾼 것·문제 목록: [docs/known-issues.md](docs/known-issues.md)
