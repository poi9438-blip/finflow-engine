# 공통 응답·오류 형식 (TC-ERR)

대상: `GlobalExceptionHandler`, `ApiResponse`, `BusinessException`

- **원칙:** 사용자에게 보여 줄 오류는 `BusinessException`으로만 보냅니다. 예상 못 한 오류의 원인(SQL문, 클래스 이름)은 서버 로그에만 남기고 응답은 500 + 일반 메시지입니다.
- **확인 방법:** 테스트용 Controller가 예외를 일부러 던지고, `TraceIdFilter`를 붙인 MockMvc로 응답을 확인합니다.

| ID         | 분류 | 확인 내용                                    | 기대 결과                                                           | 위험 | 자동화                     | 관련 |
| ---------- | ---- | -------------------------------------------- | ------------------------------------------------------------------- | ---- | -------------------------- | ---- |
| TC-ERR-201 | 정상 | 업무 오류(`BusinessException`, 409)          | 409, 공개 메시지, traceId                                           | P2   | GlobalExceptionHandlerTest |      |
| TC-ERR-202 | 보안 | 예상 못 한 오류 (메시지에 SQL문·테이블 이름) | 500 `Internal server error`, 응답에 예외 내용·예외 클래스 이름 없음 | P1   | GlobalExceptionHandlerTest | O4   |
| TC-ERR-203 | 예외 | 입력값 검증 실패 (`amount: -1`)              | 400, `필드: 메시지`                                                 | P2   | GlobalExceptionHandlerTest |      |
| TC-ERR-204 | 예외 | 깨진 JSON 본문                               | 400 `Malformed request body` (파서 메시지 노출 없음)                | P2   | GlobalExceptionHandlerTest |      |
| TC-ERR-205 | 예외 | 주소 값 타입 불일치 (숫자 자리에 글자)       | 400 `Invalid parameter: id`                                         | P3   | GlobalExceptionHandlerTest |      |
| TC-ERR-206 | 예외 | 허용하지 않는 메서드                         | 405 `Method Not Allowed` (500으로 바뀌지 않음)                      | P2   | GlobalExceptionHandlerTest | O5   |
