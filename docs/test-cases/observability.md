# 관측 · 추적 (TC-OBS)

대상: `TraceIdFilter`(요청 span 속성), `Dockerfile`의 `otel` 단계(OpenTelemetry Java 에이전트)

**현재 구현**

- **두 번호를 함께 씁니다.**
  - 업무 traceId: HomeFin이 보낸 `hfm-…` 또는 FinFlow가 만든 `ff-…`입니다. 응답·감사 기록·로그(MDC)에 남습니다([base.md](base.md) TC-BASE-101~103).
  - OTel trace id: 관측용입니다. 에이전트가 만들고, HomeFin → FinFlow → RiskWatch 호출에는 `traceparent` 헤더로 이어집니다.
  - 요청 span에 `hfm.trace_id` 속성으로 업무 traceId를 붙입니다. HomeFin·RiskWatch와 같은 속성 이름이라 Grafana(Tempo)에서 속성 하나로 세 서비스를 함께 찾습니다.
- **배치:** 배치 실행의 `batch-…` 번호는 로그(MDC)와 실행 파라미터에만 남고 span 속성은 바꾸지 않습니다. 운영자 API로 실행하면 그 요청 span의 `ff-` 번호로 찾고, 같은 추적의 로그에서 `batch-` 번호를 봅니다.
- **관측은 선택입니다.** 기본 이미지(`runtime`)에는 에이전트가 없고, 에이전트가 없으면 span 속성 코드는 아무 일도 하지 않습니다. 관측 compose(HomeFin 저장소 `observability/`)를 함께 띄울 때만 `otel` 단계를 씁니다.
- **span에 남기지 않는 것:** DB 쿼리는 값 대신 `?`로 남습니다. 요청·응답 본문과 Authorization 헤더는 기록하지 않습니다(에이전트 기본값).

| ID         | 분류 | 확인 내용                                                            | 기대 결과                                                                                                                                                    | 위험 | 자동화                                                                           | 관련 |
| ---------- | ---- | -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---- | -------------------------------------------------------------------------------- | ---- |
| TC-OBS-201 | 정상 | 요청 span 안에서 필터 실행: `X-Trace-Id: hfm-…` 있음 / 없음          | span 속성 `hfm.trace_id`가 받은 값 / 만든 `ff-` 값, 응답 헤더와 같은 값                                                                                      | P2   | TraceIdFilterSpanTest                                                            |      |
| TC-OBS-301 | 정상 | HomeFin 관측·통합 compose에서 HomeFin 신청 1번, 운영자 심사 시작 1번 | 신청: HomeFin·FinFlow span이 한 추적, `hfm.trace_id`로 찾음. 심사 시작: FinFlow·RiskWatch span이 한 추적, `ff-` 번호로 찾음. Loki에서 traceId로 FinFlow 로그 | P2   | 자동화 안 함: HomeFin 저장소의 관측 compose가 필요 (HomeFin TC-OBS-302에서 확인) |      |
