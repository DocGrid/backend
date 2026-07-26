# Embedding Job Claim 처리량·경합 성능 Benchmark 결과

- GitHub Issue: [#61](https://github.com/DocGrid/backend/issues/61)
- 브랜치: `test/61`
- Benchmark 구현 Commit: `a85bd51c27c3d51af31fc8173556ddfae70e70c9`
- 실행 일시: 2026-07-26 16:18~16:23 KST
- 테스트 종류: 실제 OpenSQL 기반 처리량·지연·자원 경합 Benchmark

## 1. 실행 결과 요약

전체 기본 Profile이 성공했다.

```text
Worker Profile   = 1, 5, 10, 20, 40
반복              = Worker별 5회
측정 Job          = 반복당 5,000개
전체 측정 Job     = 125,000개
완료 Claim 호출   = 125,380개
전체 실행 결과    = BUILD SUCCESSFUL in 4m 20s
```

25개 Profile 모두 다음 정합성을 만족했다.

```text
Worker 오류             = 0
최종 PENDING            = 0
최종 PROCESSING         = Profile마다 5,000
불완전 소유권 Job       = 0
중복 Claim Token        = 0
LOCKED 이벤트           = 전체 125,000
이벤트 중복·누락 Job    = 0
관찰 Rollback           = 0
관찰 Deadlock           = 0
```

가장 중요한 관찰 결과는 다음과 같다.

- Worker 5에서 단일 Worker 대비 중앙 TPS가 약 3.57배 증가했다.
- Worker 5와 10의 중앙 TPS는 사실상 같았지만 p99는 6.19ms에서 18.58ms로 약 3배 증가했다.
- Worker 20은 Worker 10보다 중앙 TPS가 약 2.75% 낮고 p99는 39.14ms로 증가했다.
- Worker 40은 Worker 20보다 중앙 TPS가 약 23.25% 높지만 p99가 97.63ms로 약 2.49배 증가했다.
- Worker 40에서는 Hikari 대기 Thread가 최대 20~21개였고 거의 모든 Sampling 구간에서 대기가 관찰됐다.
- PostgreSQL Lock 대기는 드물었고 Deadlock은 없었다.

이 로컬 고정 환경에서 처리량과 Tail latency의 균형점은 Worker 5 부근이다. Worker 10 이상부터는
Connection을 더 사용하면서도 Worker 5 대비 중앙 처리량 이득이 거의 없고 Tail latency가 빠르게
증가한다. 단, 이 결과는 운영 용량 산정값이 아니라 같은 환경에서 이후 변경을 비교하기 위한 첫
기준선이다.

## 2. 측정 대상과 경계

HTTP Controller가 아니라 Spring이 관리하는 실제 `EmbeddingJobClaimService` Bean을 호출했다.

측정에 포함된 비용:

```text
Worker 조회
→ Heartbeat 기준 상태 검증
→ Transaction 시작
→ PENDING Queue SELECT FOR UPDATE SKIP LOCKED
→ PROCESSING 상태 및 소유권 기록
→ LOCKED 이벤트 저장
→ Transaction Commit
```

측정에서 제외된 비용:

```text
Security Filter
HTTP 연결
Request Parsing
JSON 직렬화
파일 다운로드
Chunking
Embedding Server
Vector 저장
```

각 Worker Thread는 `EmbeddingJobClaimService.claim(workerId)`를 Queue가 빌 때까지 반복 호출한다. Service
Proxy 호출 직전부터 정상 반환 직후까지 `System.nanoTime()`으로 측정하므로 Connection 획득과 Commit이
호출 지연에 포함된다.

## 3. 구현 파일

| 파일 | 역할 |
| --- | --- |
| `build.gradle` | `claim-performance` Tag 전용 `claimPerformanceTest` Task 등록 |
| `docs/design/embedding-job-claim-performance-benchmark.md` | 범위, 동작 흐름, Profile, 정합성, 완료 기준 |
| `src/test/java/com/opensource/docgrid/domain/embedding/benchmark/EmbeddingJobClaimPerformanceBenchmark.java` | 실제 Service 기반 Benchmark와 환경·대기·정합성 수집 |
| `docs/test-results/gimin-#61-embedding-job-claim-performance.md` | 실행 환경, 원시 결과, 해석, 재현 절차 |

Production Service, Repository, Entity, Migration, Application 설정은 변경하지 않았다.

## 4. 실행 환경 Fingerprint

| 항목 | 값 |
| --- | --- |
| 실행 Commit | `a85bd51c27c3d51af31fc8173556ddfae70e70c9` |
| 실행 시작 시 작업 트리 | Clean |
| Host OS | macOS 26.5.2 |
| Host Architecture | `aarch64` |
| CPU | Apple M5, 10 Core |
| Docker 내부 가용 자원 | 10 CPU, 8,321MB |
| DB Container Platform | `linux/amd64` |
| Container별 CPU·Memory 제한 | 별도 제한 없음, Docker Desktop 가용 자원 공유 |
| PostgreSQL | OpenSQL PostgreSQL 14.6, `x86_64-pc-linux-gnu` |
| pgvector | 0.8.0 |
| DB SSL | 비활성화한 로컬 격리 연결 |
| Java | 17.0.18 |
| JVM Max Heap | 512MiB |
| Spring Boot | 3.5.16 |
| Hikari Pool | 최대 20, 최소 Idle 20 |
| Hikari Connection Timeout | 60,000ms |
| Worker Application Name | `docgrid-claim-performance-worker` |
| Benchmark Schema | `docgrid_embedding_job_claim_performance_test` |
| Sampling 주기 | 25ms |

Host는 ARM이지만 OpenSQL Container는 AMD64로 실행됐다. 따라서 다른 Architecture 또는 Docker Desktop
자원 설정에서 얻은 절대 수치와 직접 비교하지 않는다.

기존 Compose의 `local-opensql`은 과거 초기화가 완료되지 않은 상태로 재시작돼 `app` 역할이 없었고
Health Check를 통과하지 못했다. 기존 개발 볼륨은 삭제하지 않고 다음 격리 자원을 별도로 사용했다.

```text
Container = docgrid-claim-performance-opensql
Port      = 55434
Volume    = docgrid_claim_performance_opensql_data
Database  = docgrid
User      = docgrid
```

## 5. Profile과 판정 방법

### 5.1 고정 기본값

| 항목 | 값 |
| --- | ---: |
| Warm-up Job | 500 |
| 측정 Job | Profile당 5,000 |
| 반복 | Profile당 5회 |
| Worker | 1, 5, 10, 20, 40 |
| Hikari Pool | 20 |
| Sampling | 25ms |
| Profile Deadline | 300초 |

Warm-up은 Migration, JPA Metadata, Connection Pool과 Claim SQL 경로를 예열하지만 성능 결과에는 포함하지
않는다. Profile마다 Job, Worker, 이벤트와 연결 Test 데이터를 초기화하고 같은 priority·created_at
분포로 다시 Seed했다.

### 5.2 자동 실패 조건

- Worker Thread 오류
- Job 응답 중복 또는 누락
- Claim Token 중복
- 최종 Queue 미소진
- 소유권 필드 누락
- Job별 `LOCKED` 이벤트 중복 또는 누락
- Profile Deadline 초과
- PostgreSQL Deadlock 증가
- Monitoring Connection 또는 Sampler 실패

TPS나 p99의 절대값은 장비와 Docker 상태에 영향을 받으므로 자동 실패 기준으로 사용하지 않았다.

### 5.3 Percentile

성공 Claim latency를 오름차순으로 정렬한 뒤 nearest-rank 방식으로 p50, p95, p99를 계산했다. Queue
소진 후 반환되는 빈 결과의 latency는 성공 Claim 분포와 분리했다.

### 5.4 DB Transaction 관찰값

`completedClaimTransactions`는 오류 없이 Service Proxy를 반환한 성공 Claim과 빈 Queue 호출의 합이다.

`observedDatabaseCommits`는 `pg_stat_database.xact_commit`의 Profile 전후 증분이다. 이 값에는 Claim
Transaction 외에도 Monitoring Connection과 20개 Hikari Backend의 통계 경계를 확인하는 조회
Transaction이 포함된다. 따라서 정확한 Claim Commit 수로 해석하거나 합격 기준으로 사용하지 않는다.

Rollback과 Deadlock 증분은 별도로 관찰했으며 모든 Profile에서 0이었다.

## 6. 반복 중앙값

| Worker | Queue 소진 ms | TPS | p50 ms | p95 ms | p99 ms | max ms | Hikari 대기 최대 | PG Lock 대기 최대 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 13,725.20 | 364.29 | 2.69 | 3.93 | 4.61 | 15.57 | 0 | 0 |
| 5 | 3,844.43 | 1,300.58 | 3.74 | 5.32 | 6.19 | 23.30 | 0 | 0 |
| 10 | 3,842.20 | 1,301.34 | 7.34 | 12.82 | 18.58 | 34.65 | 0 | 0 |
| 20 | 3,950.73 | 1,265.59 | 13.57 | 28.84 | 39.14 | 91.27 | 0 | 0 |
| 40 | 3,205.54 | 1,559.80 | 21.10 | 61.33 | 97.63 | 215.39 | 20 | 1 |

## 7. 반복별 원시 집계

| Worker | 반복 | TPS | p50 ms | p95 ms | p99 ms | max ms | Hikari 대기 최대 | Hikari 대기 Sample | PG Lock 대기 최대 | PG Lock 대기 Sample |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 364.29 | 2.69 | 3.93 | 4.61 | 15.57 | 0 | 0/503 | 0 | 0/503 |
| 1 | 2 | 319.58 | 3.91 | 5.17 | 5.72 | 24.47 | 0 | 0/553 | 0 | 0/553 |
| 1 | 3 | 680.78 | 1.45 | 1.81 | 2.08 | 8.80 | 0 | 0/257 | 0 | 0/257 |
| 1 | 4 | 439.75 | 2.25 | 3.08 | 3.55 | 8.56 | 0 | 0/398 | 0 | 0/398 |
| 1 | 5 | 347.00 | 2.83 | 3.96 | 5.50 | 57.46 | 0 | 0/504 | 0 | 0/504 |
| 5 | 1 | 1,261.20 | 3.74 | 6.29 | 7.72 | 25.14 | 0 | 0/147 | 0 | 0/147 |
| 5 | 2 | 2,152.24 | 2.13 | 3.25 | 6.19 | 23.30 | 0 | 0/87 | 0 | 0/87 |
| 5 | 3 | 1,658.37 | 2.99 | 4.04 | 4.49 | 5.93 | 0 | 0/112 | 0 | 0/112 |
| 5 | 4 | 1,300.58 | 3.78 | 5.32 | 6.11 | 19.63 | 0 | 0/143 | 0 | 0/143 |
| 5 | 5 | 1,138.42 | 4.26 | 6.51 | 9.24 | 44.17 | 0 | 0/161 | 0 | 0/161 |
| 10 | 1 | 1,253.18 | 7.34 | 13.58 | 22.48 | 50.80 | 0 | 0/145 | 0 | 0/145 |
| 10 | 2 | 1,179.50 | 8.17 | 14.40 | 22.31 | 41.32 | 0 | 0/154 | 0 | 0/154 |
| 10 | 3 | 1,301.34 | 7.37 | 12.82 | 18.58 | 34.65 | 0 | 0/136 | 1 | 1/136 |
| 10 | 4 | 1,757.28 | 5.42 | 8.75 | 13.59 | 28.99 | 0 | 0/106 | 0 | 0/106 |
| 10 | 5 | 1,611.76 | 5.82 | 10.38 | 14.72 | 21.68 | 0 | 0/114 | 0 | 0/114 |
| 20 | 1 | 1,561.85 | 11.87 | 21.61 | 29.49 | 91.27 | 0 | 0/113 | 1 | 1/113 |
| 20 | 2 | 1,500.72 | 12.32 | 22.99 | 31.93 | 62.94 | 0 | 0/117 | 0 | 0/117 |
| 20 | 3 | 1,265.59 | 13.57 | 30.43 | 52.64 | 105.82 | 0 | 0/133 | 0 | 0/133 |
| 20 | 4 | 1,253.76 | 14.28 | 28.84 | 39.14 | 77.43 | 0 | 0/139 | 0 | 0/139 |
| 20 | 5 | 1,104.14 | 15.58 | 35.37 | 63.67 | 146.93 | 0 | 0/152 | 1 | 3/152 |
| 40 | 1 | 1,765.66 | 14.87 | 62.94 | 108.02 | 208.26 | 21 | 98/99 | 1 | 1/99 |
| 40 | 2 | 1,600.88 | 20.98 | 58.24 | 89.30 | 161.01 | 20 | 108/109 | 1 | 1/109 |
| 40 | 3 | 1,559.80 | 21.10 | 61.09 | 98.82 | 379.45 | 20 | 112/113 | 1 | 1/113 |
| 40 | 4 | 1,437.99 | 24.16 | 61.33 | 97.63 | 220.09 | 21 | 119/120 | 2 | 2/120 |
| 40 | 5 | 1,392.52 | 24.91 | 61.92 | 93.60 | 215.39 | 20 | 123/124 | 1 | 1/124 |

## 8. 해석

### 8.1 처리량 변곡점

Worker 1에서 5로 늘리면 중앙 TPS가 364.29에서 1,300.58로 증가한다. 동시에 실행할 DB Transaction을
늘린 효과가 가장 크게 나타난 구간이다.

Worker 5와 10은 중앙 TPS가 각각 1,300.58과 1,301.34로 사실상 같다. Worker 수를 두 배로 늘렸지만
Queue 소진 시간도 3,844.43ms와 3,842.20ms로 차이가 없다.

Worker 20은 Connection Pool과 Worker 수가 같지만 중앙 TPS가 1,265.59로 낮아졌다. 이 환경에서는
Connection 20개를 모두 Claim에 사용해도 처리량 이득이 없었다.

Worker 40은 중앙 TPS 1,559.80으로 가장 높았지만 이 값만으로 Worker 40을 권장하지 않는다. Worker 5
대비 처리량은 약 19.9% 높지만 p99는 약 15.8배 높다.

### 8.2 Tail latency와 Hikari Backpressure

| Worker | p99 ms | Hikari 상태 |
| ---: | ---: | --- |
| 5 | 6.19 | 대기 없음 |
| 10 | 18.58 | 대기 없음 |
| 20 | 39.14 | 대기 없음, Pool 20개 모두 Active |
| 40 | 97.63 | 최대 20~21개 Thread 대기 |

Worker 40에서는 각 반복의 Hikari 대기 Sample이 전체 Sample보다 한 건 적은 수준이었다. Profile 시작
직후 첫 Sample을 제외하면 거의 전체 측정 구간에 Connection Pool 대기가 있었다는 뜻이다.

Worker 40의 처리량 증가는 더 많은 DB 병렬 실행이 아니라 Pool 앞에 20개 추가 Thread를 대기시키는
Backpressure와 함께 얻은 값이다. 요청별 지연이 중요한 Poller에서는 이 구성을 기본값으로 사용하지
않는다.

### 8.3 PostgreSQL Lock 대기

Worker 1과 5에서는 Lock 대기가 관찰되지 않았다. Worker 10과 20에서는 일부 반복에서 1개 Session이
한두 Sample 동안 관찰됐다.

Worker 40에서는 매 반복 1~2개 Session의 Lock 대기가 1~2개 Sample에서 관찰됐다. 25ms Sampling이라
더 짧은 대기는 놓칠 수 있지만, 지속적인 Lock Queue나 Deadlock은 없었다. `SKIP LOCKED`가 Job 후보
행에서 장시간 대기하는 현상은 확인되지 않았다.

### 8.4 Worker 분포

Worker 40의 Worker별 Claim 수는 반복에 따라 최소 101~113, 최대 142~146이었다. 완전 균등 분배를
보장하지는 않지만 특정 Worker만 Queue를 독점하거나 Job이 누락되는 현상은 없었다.

## 9. 실행한 검증

| 명령 | 결과 |
| --- | --- |
| `./gradlew testClasses` | 성공 |
| 작은 Profile `claimPerformanceTest` | 성공 |
| `./gradlew test` | 성공, Benchmark 기본 제외 확인 |
| `./gradlew claimConcurrencyTest` | 성공 |
| 기본값 `./gradlew claimPerformanceTest` | 성공, 4분 20초 |
| `./gradlew build` | 성공, 12초 |

기본 `test`와 `build`는 `benchmark` Tag를 제외한다. 신규 Benchmark는 전용 Task 또는 기존
`benchmarkTest`에서만 실행된다.

원시 결과:

```text
build/test-results/claimPerformanceTest/
└─ TEST-com.opensource.docgrid.domain.embedding.benchmark.EmbeddingJobClaimPerformanceBenchmark.xml

build/reports/tests/claimPerformanceTest/
└─ index.html
```

원시 로그 접두사:

```text
CLAIM_PERFORMANCE_ENV
CLAIM_PERFORMANCE_WARMUP
CLAIM_PERFORMANCE_RESULT
CLAIM_PERFORMANCE_MEDIAN
```

## 10. 재현 절차

### 10.1 격리 OpenSQL 준비

기존 개발 DB와 충돌하지 않는 포트를 선택하고 전용 Volume을 사용한다.

```bash
export CLAIM_BENCH_DB_PASSWORD='<로컬 테스트 전용 DB 비밀번호>'

docker run -d \
  --platform linux/amd64 \
  --name docgrid-claim-performance-opensql \
  -e POSTGRES_DB=docgrid \
  -e POSTGRES_USER=docgrid \
  -e POSTGRES_PASSWORD="${CLAIM_BENCH_DB_PASSWORD}" \
  -p 55434:5432 \
  -v docgrid_claim_performance_opensql_data:/var/lib/pgsql \
  -v /absolute/path/to/backend/docker/opensql/vars.yml:/tmp/settings/vars/vars.yml:ro \
  backend-postgres:latest
```

단순 `pg_isready`가 아니라 실제 Database SQL 성공까지 기다린다.

```bash
until docker exec docgrid-claim-performance-opensql \
  psql -U docgrid -d docgrid -tAc 'SELECT 1' >/dev/null 2>&1; do
  sleep 5
done
```

pgvector 확인:

```bash
docker exec docgrid-claim-performance-opensql \
  psql -U docgrid -d docgrid \
  -c "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';"
```

### 10.2 전체 Benchmark

Secret 원문은 명령 기록이나 결과 문서에 남기지 않는다.

```bash
export CLAIM_BENCH_DB_PASSWORD='<로컬 테스트 전용 DB 비밀번호>'
export CLAIM_BENCH_JWT_SECRET='<64자 이상 로컬 테스트 전용 JWT Secret>'

DB_HOST=localhost \
DB_PORT=55434 \
DB_NAME=docgrid \
DB_USER=docgrid \
DB_PASSWORD="${CLAIM_BENCH_DB_PASSWORD}" \
DB_SSLMODE=disable \
JWT_SECRET="${CLAIM_BENCH_JWT_SECRET}" \
CLAIM_PERFORMANCE_DOCKER_CPU='<Docker 가용 CPU>' \
CLAIM_PERFORMANCE_DOCKER_MEMORY='<Docker 가용 Memory>' \
CLAIM_PERFORMANCE_CONTAINER_PLATFORM='linux/amd64' \
./gradlew claimPerformanceTest
```

### 10.3 작은 Harness 검증

`claim.performance.*` JVM 속성은 Smoke 실행에서만 기본값을 덮어쓴다.

```bash
./gradlew claimPerformanceTest \
  -Dclaim.performance.workers=1,5 \
  -Dclaim.performance.warm-up-jobs=50 \
  -Dclaim.performance.job-count=100 \
  -Dclaim.performance.repetitions=1
```

### 10.4 Schema 보존

기본 실행은 종료 시 전용 Schema를 삭제한다. 마지막 Profile을 SQL로 직접 확인해야 할 때만 다음 값을
사용한다.

```bash
KEEP_CLAIM_PERFORMANCE_SCHEMA=true ./gradlew claimPerformanceTest
```

수동 확인 후 정리:

```sql
DROP SCHEMA IF EXISTS docgrid_embedding_job_claim_performance_test CASCADE;
```

이번 전체 실행에서는 보존 설정을 사용하지 않았고 종료 후 Schema가 삭제된 것을 확인했다.

### 10.5 격리 Container 정리

다른 검증에서 사용하지 않는 것을 확인한 뒤 Benchmark 전용 자원만 정리한다.

```bash
docker rm -f docgrid-claim-performance-opensql
docker volume rm docgrid_claim_performance_opensql_data
```

## 11. 한계와 후속 사용

- 로컬 Docker Desktop과 AMD64 Emulation 결과이므로 운영 절대 성능으로 사용하지 않는다.
- 단일 Worker 반복 간 TPS 편차가 커 고정 CI Runner 없이 절대 성능 Gate를 추가하지 않는다.
- 25ms Sampling은 더 짧은 PostgreSQL Lock 대기를 놓칠 수 있다.
- `pg_stat_database` Commit은 관측용 Transaction을 포함하므로 Claim Commit 수와 동일하지 않다.
- Benchmark는 Claim Transaction만 측정하며 전체 문서 인덱싱 Lease 길이를 결정하지 않는다.
- Worker 자동 Polling을 구현할 때 이 환경의 시작 후보는 Worker 5이고, Worker 10 이상은 전체 인덱싱
  처리량과 Tail latency를 다시 측정한 뒤 선택한다.
- Queue 조건에 재시도 예약이나 만료 Lease 회수가 추가되면 동일 Profile을 다시 실행해 기준선을
  갱신한다.
