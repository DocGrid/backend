# #92 Worker Polling 및 인덱싱 실행 오케스트레이션 검증 결과

## 1. 검증 정보

- 실행일: 2026-08-03 (Asia/Seoul)
- 대상 브랜치: `feature/92`
- 애플리케이션: Spring Boot 3.5.16, Java 17
- 데이터베이스: Docker Desktop의 격리된 PostgreSQL 14.6(OpenSQL 호환) + pgvector
- 스키마: Flyway V1~V35 적용, 통합 테스트별 격리 스키마 사용
- 최종 결과: 정규 테스트 576개와 동시성 테스트 10개 통과, 실패·오류·Skip 0개

검증에는 localhost에만 노출한 작업 전용 컨테이너와 데이터 볼륨을 사용했다. 기존
`local-opensql` 컨테이너와 `opensql_data` 공유 볼륨은 변경하지 않았다. 검증 종료 후 작업 전용
컨테이너와 볼륨은 삭제했으며, 운영 Secret은 사용하거나 기록하지 않았다.

## 2. 전체 정규 테스트

실행 명령의 환경 값은 Placeholder로 대체한다.

```bash
DB_HOST=localhost \
DB_PORT='<isolated-test-port>' \
DB_NAME=docgrid \
DB_USER='<local-test-user>' \
DB_PASSWORD='<local-test-password>' \
DB_SSLMODE=disable \
JWT_SECRET='<test-only-secret>' \
./gradlew test
```

결과:

```text
BUILD SUCCESSFUL
test suites=87 tests=576 failures=0 errors=0 skipped=0
```

기본 `test` Task에서 Worker 등록·Heartbeat·상태 관리, Polling Scheduler, 실행 슬롯, 파이프라인,
Lease 갱신, 실패 보고, 종료 절차와 기존 도메인 회귀 테스트를 함께 검증했다.

## 3. PostgreSQL 동시성 검증

실행:

```bash
DB_HOST=localhost \
DB_PORT='<isolated-test-port>' \
DB_NAME=docgrid \
DB_USER='<local-test-user>' \
DB_PASSWORD='<local-test-password>' \
DB_SSLMODE=disable \
JWT_SECRET='<test-only-secret>' \
./gradlew claimConcurrencyTest
```

결과:

```text
BUILD SUCCESSFUL
test suites=3 tests=10 failures=0 errors=0 skipped=0
```

| 테스트 클래스 | 테스트 수 | 결과 |
| --- | ---: | --- |
| `EmbeddingJobClaimConcurrencyIntegrationTest` | 2 | 통과 |
| `EmbeddingJobLeaseRecoveryIntegrationTest` | 4 | 통과 |
| `WorkerOrchestrationIntegrationTest` | 4 | 통과 |

새 Worker 오케스트레이션 통합 테스트는 다음 경쟁 조건을 실제 PostgreSQL 잠금과 트랜잭션으로
검증했다.

| 검증 항목 | 결과 |
| --- | --- |
| 두 Poller가 한 Job에 경쟁할 때 Claim과 파이프라인 제출이 한 번만 발생 | 통과 |
| 실행 슬롯 2개인 Worker가 5개 Job 중 2개만 PROCESSING으로 Claim | 통과 |
| 활성 실행의 Lease 갱신이 원래 만료 시각의 복구를 차단 | 통과 |
| Lease 갱신 중단 후 동시 복구가 Job을 정확히 한 번만 재예약 | 통과 |

## 4. 표준 빌드

동일한 격리 DB와 테스트 전용 인증 설정에서 다음 명령을 실행했다.

```bash
./gradlew build
```

결과: `BUILD SUCCESSFUL`. Compile, Test, Check, Boot JAR 및 JAR 생성 단계가 모두 성공했다.

## 5. 확인된 불변식

- 여러 Worker가 같은 대기 Job을 조회해도 PostgreSQL Claim은 한 Worker에만 귀속된다.
- 한 Worker가 소유하는 PROCESSING Job 수는 로컬 실행 슬롯 수를 넘지 않는다.
- 슬롯이 없을 때 Poller는 추가 Job을 Claim하지 않고 다음 주기를 기다린다.
- 활성 파이프라인은 완료 전까지 Lease를 갱신하며, 갱신된 Job은 이전 만료 시각에 복구되지 않는다.
- Lease 갱신이 멈춘 만료 Job에 여러 복구 실행이 경쟁해도 Retry 전이는 한 번만 적용된다.
- 성공·재시도·최종 실패 경로에서 Lease 갱신과 실행 슬롯은 정리된다.
- 종료 요청 후 새 Polling은 시작되지 않고, 대기 중인 실행은 제한 시간 정책에 따라 정리된다.

## 6. 환경 진단 기록과 제한 사항

- 기존 공유 OpenSQL 볼륨은 이미지가 기대하는 내부 Role과 초기화 상태가 달라 사용할 수 없었다.
  공유 데이터를 수정하지 않고 별도 작업 전용 컨테이너와 볼륨으로 전환했다.
- 최초 일부 기존 통합 테스트 실행은 테스트용 JWT 환경 값 누락으로 Spring Context 구성 단계에서
  실패했다. 테스트 전용 값을 제공한 재실행과 최종 전체 실행은 모두 통과했다.
- 프로젝트 기본 `test` Task가 제외하는 `claim-concurrency`는 전용 Task로 별도 실행했다.
- 외부 MinIO 의존 테스트, 실제 임베딩 서버 네트워크 동작과 Benchmark는 이번 검증 범위가 아니다.
- 테스트용 컨테이너와 볼륨은 종료 시 삭제했으므로 그 안의 데이터는 복구하지 않는다.
