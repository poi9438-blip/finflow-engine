# FinFlow Engine

대출·보증 **신청을 접수하고 상태를 관리하는** 서비스입니다. HomeFin Mate(상담·계산)에서 신청이 들어오면 FinFlow가 신청을 저장하고, 심사 → 승인/거절 같은 상태 변화를 감사 기록과 함께 남깁니다.

직접 만든 FinSight 구성의 FinFlow(거래·정산 엔진)를 처음부터 다시 설계한 저장소입니다. 원본과 달라진 점은 [docs/known-issues.md](docs/known-issues.md)에 있습니다.

## 함께 쓰는 저장소

세 저장소를 같은 폴더에 나란히 받으면 HomeFin Mate에서 세 서비스를 함께 띄울 수 있습니다 ([HomeFin 실행 5번](https://github.com/poi9438-blip/homefin-mate#5-세-서비스-함께-실행-선택)).

| 저장소                                                               | 역할                                                                  | 포트             |
| -------------------------------------------------------------------- | --------------------------------------------------------------------- | ---------------- |
| [homefin-mate](https://github.com/poi9438-blip/homefin-mate)         | 사용자 화면·상담(대출 계산, 정책 추천, 통합 분석, 신청), 관측 compose | 8100 (화면 3100) |
| [finflow-engine](https://github.com/poi9438-blip/finflow-engine)     | 대출 신청 접수·상태 관리·배치                                         | 8110             |
| [riskwatch-system](https://github.com/poi9438-blip/riskwatch-system) | 심사 규칙 엔진 (판정·근거·기준 버전)                                  | 8120             |

## 서비스 구성에서의 위치

```
사용자 ─▶ HomeFin Mate (:8100) ──X-Trace-Id──▶ FinFlow (:8110) ──▶ RiskWatch (:8120, 심사 규칙)
                                                   ▲
                                         운영자 (ADMIN JWT: 상태 변경·조회·배치)
```

- **신청 접수:** HomeFin 서버가 서비스 간 토큰으로 호출합니다. 사용자 브라우저는 FinFlow를 직접 부르지 않습니다.
- **traceId:** HomeFin이 보낸 `X-Trace-Id`를 그대로 씁니다. 운영자가 직접 부르는 등 헤더가 없으면 `ff-UUID`를 만듭니다. 관측 이미지로 띄우면 요청 span에도 `hfm.trace_id`로 붙습니다.
- **심사 판정:** 운영자가 심사를 시작하면 RiskWatch 판정(평가 번호·판정·기준 버전)을 받아 신청에 저장합니다. RiskWatch가 응답하지 않아도 심사는 시작하고 "평가 불가"로 남깁니다. RiskWatch 토큰 키는 `RISKWATCH_TOKEN_SECRET`(RiskWatch의 `FINFLOW_TOKEN_SECRET`과 같은 값)입니다.

## 진행 단계

| 단계 | 내용                                                                 | 상태 |
| ---- | -------------------------------------------------------------------- | ---- |
| 1    | 뼈대: 공통 응답·오류 형식, traceId, 보안 기본값, compose, CI         | 완료 |
| 2    | 신청 상태 흐름 + 감사 기록, 서비스 토큰·운영자 로그인                | 완료 |
| 3    | 멱등성 (같은 신청이 두 번 들어와도 한 번만 저장)                     | 완료 |
| 4    | 배치 (오래 대기한 신청 만료, 일별 집계)                              | 완료 |
| 5    | 심사 시작 때 RiskWatch 판정 저장 (주택 보유·맞벌이 입력 추가)        | 완료 |
| 6    | 관측: 요청 span에 업무 traceId(`hfm.trace_id`), OTel 에이전트 이미지 | 완료 |
| 7    | 업무 지표: 신청 상태 변화(접수 포함), 심사 시작 때 RiskWatch 결과    | 완료 |

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

운영자 로그인과 신청 목록 (`.env`에 `OPERATOR_EMAIL`·`OPERATOR_PASSWORD`를 넣었을 때):

```bash
curl -s -X POST http://localhost:8110/api/auth/login -H "Content-Type: application/json" \
  -d '{"email":"<OPERATOR_EMAIL>","password":"<OPERATOR_PASSWORD>"}'        # → data.token
curl -s http://localhost:8110/api/admin/applications -H "Authorization: Bearer <token>"
```

신청 접수(`POST /api/applications`)는 HomeFin 서버가 서비스 토큰과 `Idempotency-Key`로 호출합니다. 만료(매일 03:00)·일별 집계(03:30) 배치는 한국 시각 기준으로 자동 실행됩니다. API 전체는 [업무 명세 6장](docs/spec.md#6-api)에 있습니다.

| API                                                       | 주체    | 내용                           |
| --------------------------------------------------------- | ------- | ------------------------------ |
| `POST /api/auth/login`                                    | 누구나  | 운영자 로그인                  |
| `POST /api/applications`                                  | SERVICE | 신청 접수                      |
| `GET /api/applications/{id}`                              | SERVICE | 신청자 본인 신청 조회          |
| `POST /api/applications/{id}/cancel`                      | SERVICE | 신청자 취소                    |
| `GET /api/admin/applications`                             | ADMIN   | 목록 (상태·상품 필터, 페이지)  |
| `GET /api/admin/applications/{id}`                        | ADMIN   | 상세 + 상태 이력               |
| `POST /api/admin/applications/{id}/review·approve·reject` | ADMIN   | 심사 시작·승인·거절            |
| `POST /api/admin/batch/expire`                            | ADMIN   | 만료 배치 지금 실행            |
| `POST /api/admin/batch/daily-stats?date=`                 | ADMIN   | 그날(한국 날짜) 집계 지금 실행 |
| `GET /api/admin/stats/daily?from=&to=`                    | ADMIN   | 일별 집계 조회 (최대 92일)     |

### 2. 테스트

```bash
./mvnw test                   # Windows: mvnw.cmd test
python scripts/tc_status.py   # 테스트 명세와 결과 대조
```

### 3. 관측 (선택)

기본 이미지에는 관측 도구가 없습니다. Dockerfile의 `otel` 빌드 단계에 OpenTelemetry Java 에이전트(2.33.0, 체크섬 확인)가 들어 있고, HomeFin 저장소의 관측 compose가 이 단계를 씁니다.

```bash
# homefin-mate 폴더에서 (세 저장소를 나란히 받아 둔 상태)
docker compose -f docker-compose.yml -f full/docker-compose.full.yml -f observability/docker-compose.observability.yml -f observability/docker-compose.observability.full.yml up -d --build
```

- Grafana(http://localhost:3000) → Tempo에서 `{span.hfm.trace_id="…"}`로 찾으면 FinFlow span이 HomeFin·RiskWatch span과 한 추적으로 보입니다. 속성 이름은 세 서비스가 같습니다.
- Loki에서 `{service_name="finflow"} | traceId="…"`로 같은 번호의 로그를 찾습니다.
- 업무 지표: `finflow_application_status_changes_total{product_code, to_status}`(커밋된 상태 이력만), `finflow_riskwatch_evaluations_total{status}`. HomeFin 대시보드 "FinSight 서비스"와 알림 "RiskWatch 평가 불가 (FinFlow 심사 시작)"이 씁니다.

## 문서

- 업무 명세 (무엇을 하는지, 상태·API·데이터): [docs/spec.md](docs/spec.md)
- 테스트 기준·명세: [docs/TESTING.md](docs/TESTING.md), [docs/test-cases/](docs/test-cases/README.md)
- 원본에서 바꾼 것·문제 목록: [docs/known-issues.md](docs/known-issues.md)
