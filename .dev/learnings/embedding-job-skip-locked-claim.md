# Embedding Job Claim의 행 잠금과 테스트 스키마

## 문제

여러 Worker가 PENDING Job을 먼저 조회하고 나중에 PROCESSING으로 변경하면 같은 Job을 동시에 선택할
수 있다. JVM 내부 Lock은 여러 애플리케이션 인스턴스가 공유하지 않으므로 분산 Worker의 중복 Claim을
막지 못한다.

## 적용 패턴

Queue 조회에 `FOR UPDATE SKIP LOCKED`를 사용하고, 조회부터 상태·Lease·이벤트 저장까지 하나의
Transaction으로 처리한다.

```text
Transaction 시작
→ PENDING Job SELECT ... LIMIT 1 FOR UPDATE SKIP LOCKED
→ PROCESSING + Worker + Claim Token + Lease 기록
→ LOCKED 이벤트 저장
→ Commit 후 행 잠금 해제
```

`SKIP LOCKED`는 다른 Transaction이 잠근 최우선 Job을 기다리지 않고 다음 PENDING Job을 선택하게
한다. Repository 잠금 조회가 Transaction 밖에서 실행되면 메서드 반환과 함께 잠금이 끝나므로 Claim
상태 변경까지 보호하지 못한다.

## 검증

실제 OpenSQL에서 서로 다른 Thread와 `REQUIRES_NEW` Transaction으로 다음을 확인한다.

- 한 Transaction이 최우선 Job을 잠근 동안 다른 Transaction은 다음 Job 선택
- 두 Worker가 하나의 Job을 동시에 Claim해도 성공은 한 건
- 최종 상태 PROCESSING, 소유 Worker와 UUID Token 및 Lease 기록
- LOCKED 이벤트 한 건 저장

## pgvector와 격리 테스트 스키마

pgvector Extension은 DB의 `public` 스키마에 설치된다. JDBC `currentSchema`를 격리 테스트 스키마
하나로만 지정하면 Flyway V32가 `vector` 타입을 찾지 못한다.

테스트 URL의 Search Path는 다음 순서를 사용한다.

```text
currentSchema=${TEST_DB_SCHEMA},public
```

Flyway의 기본 생성 대상은 `TEST_DB_SCHEMA`로 유지하면서 Extension 타입과 연산자는 `public`에서
찾을 수 있다.
