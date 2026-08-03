# #90 인덱싱 Job Lease 갱신 및 만료 복구 검증 결과

## 1. 검증 정보

- 실행일: 2026-08-03 (Asia/Seoul)
- 대상 브랜치: `feature/90`
- 애플리케이션: Spring Boot 3.5.16, Java 17
- 데이터베이스: 격리된 PostgreSQL 14(OpenSQL 호환) + pgvector
- 검증 범위: 전체 기본 Build와 Lease 복구 전용 동시성 Test
- 최종 결과: 기본 Test 547개와 동시성 Test 4개 통과, 실패·오류·Skip 0개

기존 개발 데이터와 영구 Volume은 사용하거나 변경하지 않았다. 검증에는 localhost에만 노출한 일회용
PostgreSQL 컨테이너를 사용했고, Flyway Migration과 Test Class별 격리 Schema를 적용했다. 인증 관련
환경 값은 테스트 전용 설정을 사용했으며 실제 값은 기록하지 않는다.

## 2. 전체 회귀 검증

실행 명령의 환경 값은 Placeholder로 대체한다.

```bash
DB_HOST=localhost \
DB_PORT='<isolated-test-port>' \
DB_NAME=docgrid \
DB_USER='<local-test-user>' \
DB_PASSWORD='<local-test-password>' \
DB_SSLMODE=disable \
JWT_SECRET='<test-only-secret>' \
./gradlew clean build
```

결과:

```text
BUILD SUCCESSFUL
tests=547 failures=0 errors=0 skipped=0
```

기본 `test` Task에서 다음 영역을 함께 회귀 검증했다.

- Lease 갱신 API의 정상·Token 불일치·만료·상태 불일치 응답
- `EmbeddingJob`의 Lease 갱신과 Retry·최종 실패 전이
- DEAD Worker 확정과 오래 만료된 Job 후보 조회
- 복구 Service의 소유권 재확인, Attempt 처리와 실패 이벤트 기록
- Scheduler의 단계별 실패 격리와 후보별 계속 진행

## 3. Lease 복구 동시성 검증

실행:

```bash
DB_HOST=localhost \
DB_PORT='<isolated-test-port>' \
DB_NAME=docgrid \
DB_USER='<local-test-user>' \
DB_PASSWORD='<local-test-password>' \
DB_SSLMODE=disable \
./gradlew claimConcurrencyTest \
  --tests 'com.opensource.docgrid.domain.embedding.integration.EmbeddingJobLeaseRecoveryIntegrationTest'
```

결과:

```text
BUILD SUCCESSFUL
tests=4 failures=0 errors=0 skipped=0
```

| 검증 항목 | 결과 |
| --- | --- |
| 만료 후보를 오래된 Lease 순서로 제한 조회 | 통과 |
| 두 복구 실행이 같은 Job에 경쟁할 때 단일 상태 전이 | 통과 |
| Attempt가 없는 Job 복구 시 새 Attempt를 합성하지 않음 | 통과 |
| Retry 소진 Job을 최종 실패 상태로 전이 | 통과 |

PostgreSQL `timestamp` 정밀도로 나노초 경계가 절삭되는 문제를 확인해, 경계 Fixture를 1초 뒤로
조정했다. 이후 같은 조건에서 전용 동시성 Test 4개가 모두 통과했다.

## 4. 확인된 불변식

- Lease 갱신은 `PROCESSING` 상태, 일치하는 Worker·Claim Token, 만료 전 Lease에서만 성공한다.
- 복구 후보 Snapshot 조회와 실제 복구 Transaction을 분리한다.
- 실제 복구는 Job 행 잠금 뒤 Lease 만료와 상태를 다시 확인한다.
- 같은 만료 Job에 여러 복구 실행이 경쟁해도 하나만 Retry 또는 최종 실패를 적용한다.
- Retry 복구는 Worker·Claim Token·Lease를 제거하고 다음 실행을 `PENDING`으로 돌린다.
- Retry를 모두 소진하면 Job과 관련 상태를 최종 실패 정책으로 전이한다.
- 기존 Attempt가 없는 Job에는 복구 과정에서 임의의 Attempt를 만들지 않는다.
- 한 후보의 복구 실패가 같은 Batch의 나머지 후보 처리를 중단하지 않는다.

## 5. 환경 진단 기록과 제한 사항

- 기존 Compose 데이터 Volume은 컨테이너 Image의 기대 Role과 달라 초기 접속에 실패했다. 기존
  Volume을 수정하지 않고 별도 일회용 데이터베이스로 전환했다.
- Image 기본 Bootstrap 경로에서는 SSL·Host 인증 설정이 Test 접속 조건과 맞지 않았다. localhost
  전용 Test Instance로 재구성한 뒤 Flyway와 Test를 실행했다.
- 최초 전체 Test의 Application Context 실패 11건은 테스트용 JWT 설정 누락 때문이었다. 테스트 전용
  설정을 주입한 재실행에서는 547개가 모두 통과했다.
- 프로젝트 기본 Build가 제외하는 외부 MinIO 의존 태그와 Benchmark는 이번 검증 범위가 아니다.
- 운영 부하에서의 복구 처리량, Scheduler 주기와 Batch 크기 적정성은 측정하지 않았다.
