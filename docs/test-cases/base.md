# 뼈대 (TC-BASE)

대상: `SecurityConfig`, `TraceIdFilter`, `TraceIds`, `JacksonTimeConfig`, `application.yml`(actuator)

- **traceId 규칙:** 번호는 요청을 시작한 서비스만 만듭니다. HomeFin이 보낸 `X-Trace-Id`(`hfm-…`)가 형식(영문·숫자·`._-`, 64자 이하)에 맞으면 그대로 쓰고, 없거나 틀리면 `ff-UUID`를 만듭니다.
- **보안 기본값:** 뼈대 단계에는 인증 방법이 없어서 `/actuator/health` 외 모든 주소가 401입니다. 서비스 토큰·운영자 JWT는 다음 단계에서 추가합니다.
- **경계·보안 관점:** traceId 64/65자 경계와 허용 안 되는 문자(로그 위조 방지)를 확인합니다. 정상·예외 관점은 아래 행에 있습니다.

| ID          | 분류 | 확인 내용                                                                | 기대 결과                                                                   | 위험 | 자동화                                                             | 관련 |
| ----------- | ---- | ------------------------------------------------------------------------ | --------------------------------------------------------------------------- | ---- | ------------------------------------------------------------------ | ---- |
| TC-BASE-101 | 정상 | 로그인 없이 `/actuator/health`, `X-Trace-Id` 없음                        | 200 `UP`, 세부 정보(components) 없음, 응답 헤더에 `ff-UUID`                 | P1   | BaseApiTest                                                        | O3   |
| TC-BASE-102 | 정상 | HomeFin이 보낸 `X-Trace-Id: hfm-…`로 API 호출                            | 응답 헤더와 오류 본문의 traceId가 받은 값 그대로                            | P1   | BaseApiTest                                                        | O3   |
| TC-BASE-103 | 보안 | 형식이 틀린 `X-Trace-Id` (허용 안 되는 문자 / 65자)                      | 받은 값을 버리고 새 `ff-UUID`                                               | P2   | BaseApiTest                                                        | O3   |
| TC-BASE-104 | 보안 | 인증 없이 API 호출                                                       | 401, `{status: ERROR, message: Unauthorized, traceId}`, traceId = 응답 헤더 | P1   | BaseApiTest                                                        | O6   |
| TC-BASE-105 | 보안 | 인증된 요청으로 `/actuator/env`, `/actuator/beans`                       | 404 (웹으로는 health만 공개)                                                | P1   | BaseApiTest                                                        |      |
| TC-BASE-106 | 예외 | 인증된 요청으로 없는 주소                                                | 404 `Not Found`, 공통 오류 형식 + traceId                                   | P2   | BaseApiTest                                                        | O5   |
| TC-BASE-107 | 정상 | 요청이 끝난 뒤 같은 스레드의 로그용 traceId(MDC)                         | 지워져 있음                                                                 | P2   | BaseApiTest                                                        |      |
| TC-BASE-201 | 경계 | `TraceIds.resolve`: 형식 맞음 / 64자 / 없음 / 빈 값 / 65자 / 줄바꿈 포함 | 앞의 둘은 그대로, 나머지는 새 `ff-UUID`, 매번 다른 번호                     | P1   | TraceIdsTest                                                       | O3   |
| TC-BASE-202 | 정상 | 응답의 `LocalDateTime` (JVM 시간대 UTC / 서울)                           | `…Z` / `…+09:00` (시간대 포함)                                              | P2   | JacksonTimeConfigTest                                              |      |
| TC-BASE-301 | 보안 | `.env`에 DB 비밀번호 없이 `docker compose up`                            | compose가 시작하지 않고 설정하라는 메시지                                   | P1   | 자동화 안 함: compose 동작이라 실제 실행으로 확인 (PR에 결과 기록) | O1   |
