# Embedding Job Claim 동시성 테스트 학습

- Hikari의 `connectionTimeout`은 Spring `Duration` 문자열이 아니라 밀리초 `long` 속성이다. 동적 설정에
  `60s`를 넣으면 Binding이 실패하므로 `60000` 또는 `60_000L`을 사용한다.
- OpenSQL 최초 초기화 중 `pg_isready`는 사용자 Database 생성 전에도 Server가 연결 가능하다고 응답할 수
  있다. 준비 확인은 실제 대상 Database에 `psql ... -tAc 'SELECT 1'`이 성공하는지 확인한다.
- Java Worker Task 수와 DB 동시 Transaction 수는 Hikari Pool이 분리한다. 100개 Task를 시작하면서 Pool을
  20개로 제한하면 Connection 고갈 대신 제한된 Backpressure 아래의 Claim 정합성을 검증할 수 있다.
- 테스트 스키마를 수동 확인용으로 보존할 때는 마지막 시나리오가 무엇인지 결정적이어야 한다. 각 테스트의
  데이터 독립성은 `@BeforeEach`로 유지하고, 수동 검증 결과만 고정하기 위해 명시적 실행 순서를 사용한다.
