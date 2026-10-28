# 관측 · 추적 (TC-OBS)

대상: `TraceIdFilter`(요청 span 속성), `ApplicationMetrics`·`AfterCommit`(업무 지표), `Dockerfile`의 `otel` 단계(OpenTelemetry Java 에이전트)

**현재 구현**

- **두 번호를 함께 씁니다.**
  - 업무 traceId: HomeFin이 보낸 `hfm-…` 또는 FinFlow가 만든 `ff-…`입니다. 응답·감사 기록·로그(MDC)에 남습니다([base.md](base.md) TC-BASE-101~103).
  - OTel trace id: 관측용입니다. 에이전트가 만들고, HomeFin → FinFlow → RiskWatch 호출에는 `traceparent` 헤더로 이어집니다.
  - 요청 span에 `hfm.trace_id` 속성으로 업무 traceId를 붙입니다. HomeFin·RiskWatch와 같은 속성 이름이라 Grafana(Tempo)에서 속성 하나로 세 서비스를 함께 찾습니다.
- **배치:** 배치 실행의 `batch-…` 번호는 로그(MDC)와 실행 파라미터에만 남고 span 속성은 바꾸지 않습니다. 운영자 API로 실행하면 그 요청 span의 `ff-` 번호로 찾고, 같은 추적의 로그에서 `batch-` 번호를 봅니다.
- **업무 지표:**
  - `finflow.application.status_changes`: 상태 이력이 저장된 수(접수 `RECEIVED` 포함, 속성 `product_code`·`to_status`). 커밋된 뒤에만 세므로 재전송·409·롤백은 세지 않습니다.
  - `finflow.riskwatch.evaluations`: 심사 시작 때 RiskWatch 결과(속성 `status`: `EVALUATED`·`UNAVAILABLE`·`INPUT_MISSING`).
  - Prometheus 이름은 `…_total`입니다(`finflow_application_status_changes_total`). 정해진 조합은 시작할 때 0으로 만들어 둡니다(처음 생긴 값은 `increase()`가 증가로 보지 않아 알림이 첫 건을 놓치므로).
- **관측은 선택입니다.** 기본 이미지(`runtime`)에는 에이전트가 없고, 에이전트가 없으면 span 속성 코드는 아무 일도 하지 않습니다. 관측 compose(HomeFin 저장소 `observability/`)를 함께 띄울 때만 `otel` 단계를 씁니다.
- **span에 남기지 않는 것:** DB 쿼리는 값 대신 `?`로 남습니다. 요청·응답 본문과 Authorization 헤더는 기록하지 않습니다(에이전트 기본값).

| ID         | 분류 | 확인 내용                                                                   | 기대 결과                                                                                                                                                    | 위험 | 자동화                                                                           | 관련 |
| ---------- | ---- | --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---- | -------------------------------------------------------------------------------- | ---- |
| TC-OBS-101 | 정상 | 신청 접수 → 같은 키 재전송 → 같은 신청자·상품 새 키(409) → 심사 시작 → 승인 | `finflow.application.status_changes`: `RECEIVED` 1(재전송·409는 세지 않음), `REVIEWING` 1, `APPROVED` 1 (상품별)                                             | P2   | ApplicationMetricsApiTest                                                        |      |
| TC-OBS-102 | 정상 | 심사 시작: RiskWatch 판정 / RiskWatch 응답 없음                             | `finflow.riskwatch.evaluations` `EVALUATED` 1 / `UNAVAILABLE` 1, 두 지표의 다른 조합은 시작부터 0                                                            | P2   | ApplicationMetricsApiTest                                                        |      |
| TC-OBS-201 | 정상 | 요청 span 안에서 필터 실행: `X-Trace-Id: hfm-…` 있음 / 없음                 | span 속성 `hfm.trace_id`가 받은 값 / 만든 `ff-` 값, 응답 헤더와 같은 값                                                                                      | P2   | TraceIdFilterSpanTest                                                            |      |
| TC-OBS-202 | 예외 | 트랜잭션 안에서 지표 기록: 커밋 / 롤백 / 트랜잭션 밖                        | 커밋 뒤에만 셈 / 롤백이면 세지 않음 / 밖이면 바로 셈                                                                                                         | P2   | AfterCommitTest                                                                  |      |
| TC-OBS-301 | 정상 | HomeFin 관측·통합 compose에서 HomeFin 신청 1번, 운영자 심사 시작 1번        | 신청: HomeFin·FinFlow span이 한 추적, `hfm.trace_id`로 찾음. 심사 시작: FinFlow·RiskWatch span이 한 추적, `ff-` 번호로 찾음. Loki에서 traceId로 FinFlow 로그 | P2   | 자동화 안 함: HomeFin 저장소의 관측 compose가 필요 (HomeFin TC-OBS-302에서 확인) |      |
