# 테스트 기준

HomeFin Mate에서 만든 테스트 체계를 그대로 씁니다. 자세한 배경은 [HomeFin의 테스트 기준](https://github.com/poi9438-blip/homefin-mate/blob/main/docs/TESTING.md)에 있습니다.

## 실행

```bash
./mvnw test                     # 테스트 + JaCoCo (target/site/jacoco/index.html)
python scripts/tc_status.py     # 명세 ↔ 테스트 ↔ 결과 대조 (--strict: 문제 있으면 종료 코드 1)
```

- 특정 클래스만: `./mvnw test -Dtest=BaseApiTest`
- **제품 코드를 바꿔 가며 확인했다면 `target`을 지우고 다시 실행합니다.** 이전 빌드 결과물이 남아 엉뚱한 결과가 나올 수 있습니다.
- DB는 H2 메모리 DB(MySQL 모드, `application-test.yml`)를 씁니다. 테이블이 생기는 단계부터 실제 MySQL로 확인하는 테스트(Testcontainers)를 추가합니다.

## 규칙

1. 명세(`docs/test-cases`)와 테스트를 `@DisplayName("[TC-…] …")`의 ID로 연결합니다.
2. 정상·예외·경계·보안 네 관점을 보고, 해당 없는 관점은 이유를 명세에 적습니다.
3. 처음부터 통과한 중요한 테스트는 **변이 검사**로 확인합니다. 제품 코드를 일부러 틀리게 바꿔 테스트가 실패하는지 보고, 판단은 종료 코드가 아니라 결과 XML로 합니다.
4. 통과시키려고 테스트를 지우거나 단언을 약하게 바꾸지 않습니다.
5. 실행하지 않은 테스트를 통과로 세지 않습니다 (결과 XML이 없으면 "미실행").

## 우선순위

| 등급 | 뜻                                                    |
| ---- | ----------------------------------------------------- |
| P0   | 돈·신청 상태가 틀어지거나 개인정보가 새는 문제        |
| P1   | 보안 기본값, 다른 서비스와의 약속(traceId, 응답 형식) |
| P2   | 오류 응답·운영 편의                                   |
| P3   | 문구·형식                                             |
