# 검색 실패 원장·트랜잭션 경계 검증 결과

- 관련 이슈: #320
- 실행 일시: 2026-09-11 (Asia/Seoul)
- 기준 Commit: `55e6dc7` (`develop`)
- 환경: Java 17, Spring Boot 3.5.16, Hibernate 6.6.53, PostgreSQL 17.8, HikariCP
- 격리 Schema: `docgrid_test`

## 검증 목적

- 임베딩 실패와 검색 단계 실패 뒤 `FAILED` 검색 원장이 실제 DB에 남아야 한다.
- 외부 임베딩 호출 중 Spring Transaction과 DB Connection을 점유하지 않아야 한다.
- 검색 결과·질의 임베딩 정보·`SUCCESS` 상태는 한 Transaction으로 확정돼야 한다.
- 결과 저장이 실패하면 부분 결과와 `SUCCESS`가 롤백되고, 기존 원장은 `FAILED`로 수렴해야 한다.
- 늦게 도착한 실패 처리는 이미 완료된 `SUCCESS`를 덮어쓰지 않아야 한다.

## 측정 방법

`SearchTransactionBoundaryIntegrationTest`는 테스트 자체의 Transaction을 끄고 실제 PostgreSQL을
사용했다. 임베딩 Client와 검색 장애 지점만 mock으로 제어했으며, 임베딩 호출 순간에 다음 값을
수집했다.

- `TransactionSynchronizationManager.isActualTransactionActive()`
- `HikariPoolMXBean.getActiveConnections()`
- 예외가 반환된 뒤 `search_queries`의 전체·`FAILED`·`PROCESSING` 행 수

임베딩 실패와 원장 생성 이후 검색 실패 두 시나리오를 같은 기준으로 각각 실행했다.

## 수정 전·후 결과

| 지표 | 수정 전 (`55e6dc7`) | 수정 후 | 변화 |
|---|---:|---:|---:|
| 실패 시나리오 원장 보존 | 0/2 | 2/2 | 0% → 100% |
| 임베딩 호출 중 Transaction 활성 | 2/2 | 0/2 | 100% → 0% |
| 임베딩 호출 순간 활성 DB Connection | 시나리오별 1개 | 시나리오별 0개 | 1 → 0 |
| 예외 후 `search_queries` 행 | 시나리오별 0건 | 시나리오별 1건 | 0 → 1 |
| 예외 후 `FAILED` 행 | 시나리오별 0건 | 시나리오별 1건 | 0 → 1 |
| 예외 후 `PROCESSING` 잔존 | 시나리오별 0건 | 시나리오별 0건 | 변화 없음 |

수정 전 실제 Assertion 값은 두 시나리오 모두 다음과 같았다.

```text
FailureMetrics[
  transactionActiveDuringEmbedding=true,
  activeConnectionsDuringEmbedding=1,
  queryRows=0,
  failedRows=0,
  processingRows=0
]
```

수정 후 두 시나리오의 값은 다음과 같았다.

```text
FailureMetrics[
  transactionActiveDuringEmbedding=false,
  activeConnectionsDuringEmbedding=0,
  queryRows=1,
  failedRows=1,
  processingRows=0
]
```

## 원자성 추가 검증

| 시나리오 | 최종 검색 원장 | 검색 결과 | 임베딩 정보 |
|---|---|---:|---|
| 접근 가능한 문서가 없는 정상 검색 | `SUCCESS` | 0건 | 모델·Vector 저장 |
| 성공 뒤 늦은 실패 처리 | `SUCCESS` 유지 | 0건 | 유지 |
| 존재하지 않는 Chunk FK로 결과 INSERT 실패 | `FAILED` | 0건 | 성공 Transaction과 함께 롤백되어 null |

조건부 실패 UPDATE는 `PROCESSING` 상태만 변경한다. 정상 검색에 뒤늦게 `markFailed`를 호출한
검증에서는 영향받은 행이 0건이었고 `SUCCESS` 상태가 유지됐다.

## 성능 수치 해석

이번 변경은 임베딩 계산 자체를 빠르게 만들지 않으므로 HTTP 응답 시간 단축 수치로 표현하지 않았다.
측정된 개선은 외부 호출 구간의 활성 DB Connection이 요청당 1개에서 0개로 줄었다는 점이다.
따라서 절감되는 Connection 점유 시간은 각 요청의 실제 임베딩 대기 시간과 같고, 동시 요청에서
Connection Pool 고갈 가능성을 낮춘다. 실제 동시 처리량은 별도 부하 테스트 없이 추정하지 않는다.

## 실행 명령과 결과

수정 전 동일 테스트를 실행해 목표 동작과 반대인 실제값을 확인했다.

```bash
DB_PORT=55433 ./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.search.integration.SearchTransactionBoundaryIntegrationTest'
```

- 수정 전: 2 tests, 2 failed
- 수정 후: 4 tests, 0 failed

검색 도메인과 영향받은 RAG 명령 테스트도 함께 실행했다.

```bash
DB_PORT=55433 ./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.search.*' \
  --tests 'com.opensource.docgrid.domain.rag.service.command.RagResponseCommandServiceTest'
```

- 결과: 53 tests, 0 failed, 0 errors, 0 skipped

전체 회귀 테스트와 배포 Artifact 빌드도 실행했다.

```bash
DB_PORT=55433 \
JWT_SECRET=test-only-secret-key-with-at-least-32-characters \
./backend/gradlew -p backend build
```

- 결과: 1,137 tests, 0 failed, 0 errors, 0 skipped
- 빌드 결과: `BUILD SUCCESSFUL in 39s`
