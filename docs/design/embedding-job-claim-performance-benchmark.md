# Embedding Job Claim 처리량·경합 성능 Benchmark 설계

## 1. 목표

여러 Worker가 동시에 `PENDING` Embedding Job을 Claim할 때 실제 OpenSQL 환경에서 처리량, 호출 지연,
Connection Pool 대기, PostgreSQL Lock 대기가 어떻게 변하는지 재현 가능한 기준선으로 측정한다.

기존 정합성 테스트는 한 Job의 소유권이 정확히 한 번만 Commit되고 대량 Queue가 중복과 누락 없이
소진되는지를 검증한다. 이번 작업은 그 불변식을 유지하면서 Worker 동시성 증가가 다음 지표에 미치는
영향을 수치로 남기는 것이 목적이다.

- 초당 성공 Claim 수
- 성공 Claim 호출의 p50, p95, p99, 최대 지연
- Queue 전체 소진 시간
- Hikari Connection 대기 Thread 수
- PostgreSQL Lock 대기 Session 수
- Worker별 Claim 처리 분포
- Commit, Rollback, Deadlock 관찰값

측정 결과는 PostgreSQL Queue가 현재 규모에 충분한지 판단하고, Worker 실행 Thread 수와 Hikari Pool
크기를 정하며, 향후 외부 Message Broker 검토가 필요한 시점을 판단하는 근거로 사용한다.

## 2. 비범위

- Production Service, Repository, Entity, Controller, Migration 변경
- Claim API의 HTTP, 인증, JSON 직렬화 성능
- 파일 다운로드, 파싱, Chunking, Embedding Server, Vector 저장 성능
- Lease 연장, 만료 Lease 회수, 재Claim
- 재시도 Queue와 실패 복구
- 실제 문서 한 건의 전체 인덱싱 p95 또는 p99
- Kafka, RabbitMQ, SQS 등 Message Broker 도입
- 운영 환경의 CPU, Memory, Network 용량 산정
- 최초 기준선에서의 절대 TPS 또는 p99 자동 실패 기준
- Worker별 처리량의 완전한 공정성 보장
- Production 코드에 성능 측정용 로그나 Metric 추가

이번 Benchmark의 실행 시간은 로컬 장비, Docker 자원, ARM에서 AMD64 Image를 실행하는 비용, DB Cache
상태에 영향을 받는다. 따라서 서로 다른 환경에서 얻은 절대 수치를 직접 비교하지 않는다.

## 3. 현재 기준선

### 3.1 구현된 Claim 경로

현재 `develop`의 Claim 경로는 다음 요소를 제공한다.

| 구성요소 | 현재 책임 |
| --- | --- |
| `EmbeddingJobClaimService` | Worker 검증부터 Job Claim, 이벤트 저장까지 하나의 짧은 Transaction으로 처리 |
| `EmbeddingJobRepository` | `PENDING` Queue에서 우선순위 후보를 `FOR UPDATE SKIP LOCKED`로 조회 |
| `EmbeddingJob` | `PENDING`에서 `PROCESSING`으로 전환하며 Worker, Token, Lease를 함께 기록 |
| `IndexingEventRepository` | Claim Transaction 안에서 `LOCKED` 이벤트 저장 |
| `IndexingWorkerProperties` | Heartbeat 만료 기준과 Lease 길이 제공 |

Repository는 높은 priority, 오래된 생성 시각, 작은 ID 순서로 Job을 선택한다. 다른 Transaction이 잠근
후보는 기다리지 않고 건너뛴다.

### 3.2 기존 검증

기존 `EmbeddingJobClaimConcurrencyIntegrationTest`는 실제 OpenSQL에서 다음 정합성을 검증한다.

- 100개 Worker가 Job 하나를 동시에 Claim해도 성공은 한 건
- 20개 Worker가 Job 1,000개를 중복과 누락 없이 소진
- 응답과 DB의 Worker, Claim Token, Lease가 일치
- Job마다 `LOCKED` 이벤트가 정확히 한 건
- 제한된 Hikari Pool을 거쳐도 소유권 정합성이 유지됨

이 테스트는 TPS, 평균 지연, p95, p99, DB Lock 대기 시간을 합격 기준으로 사용하지 않는다.

### 3.3 기존 Benchmark 기반

저장소에는 `@Tag("benchmark")` 기반 OpenSQL Benchmark와 `benchmarkTest` Gradle Task가 있다.
`EmbeddingModelIndexBenchmark`는 다음 패턴을 제공한다.

- 별도 Test Schema
- Warm-up과 측정 실행 분리
- 반복 측정값 집계
- 실행 계획과 Buffer 관찰
- Benchmark를 기본 `test`와 `build`에서 제외
- 측정값은 관찰값으로 기록하고 불안정한 절대 시간은 자동 실패 기준으로 사용하지 않음

이번 작업은 이 기반을 재사용하되, 단일 SQL 조회가 아니라 실제 Spring Transaction Proxy를 통과하는
Claim Service의 다중 Thread 실행을 측정한다.

### 3.4 선택한 설계 기준선

설계 기준은 `origin/develop`이다. 현재 Checkout에는 테스트 결과 문서 위치와 내부 표기 정리 변경만
있으며 Production Claim 코드 차이는 없다.

## 4. 가정과 결정 사항

### 4.1 확인된 가정

- Benchmark는 전용 OpenSQL 14.6 컨테이너와 pgvector 0.8.0 환경에서 단독 실행한다.
- 테스트용 DB 사용자에게 자신의 Session을 조회할 권한이 있다.
- Benchmark 실행 중 동일 DB에서 다른 테스트나 애플리케이션을 실행하지 않는다.
- Claim 대상은 현재 Production 범위와 동일하게 `PENDING` Job만 사용한다.
- Worker는 유효한 `ACTIVE` 상태와 충분히 최근 Heartbeat를 가진다.

### 4.2 Benchmark 도구 선택

기존 JUnit 5, Spring Boot Test, JdbcTemplate, Java Executor를 사용한다. JMH는 추가하지 않는다.

JMH는 CPU 연산이나 짧은 JVM 코드의 Microbenchmark에는 유용하지만 이번 측정의 지배 요소는 외부
OpenSQL, Transaction Commit, Connection Pool, 다중 Session 경합이다. JMH를 추가해도 Docker와 DB
환경의 변동성을 제거하지 못하며 새로운 Plugin과 실행 체계만 늘어난다.

### 4.3 측정 경로 선택

HTTP Controller가 아니라 실제 `EmbeddingJobClaimService` Bean을 호출한다.

- 포함되는 비용: Worker 조회, Transaction 시작과 Commit, Queue 조회, JPA Dirty Checking, 이벤트 저장,
  Connection 획득
- 제외되는 비용: Security Filter, HTTP 연결, Request Parsing, JSON 직렬화

이번 목적은 Claim 핵심 Transaction과 DB Queue 확장성을 측정하는 것이므로 Service 경계가 적합하다.

### 4.4 Lock 비교 방식

Production의 `SKIP LOCKED` 경로를 Worker 동시성 수준별로 실행하고, Worker 증가에 따른 TPS, 지연,
Hikari 대기, DB Lock 대기 변화를 비교한다.

Benchmark 전용으로 `SKIP LOCKED`를 제거한 가짜 Claim 구현을 만들지는 않는다. 대조 구현은 Production
Service와 다른 경로를 복제해야 하므로 Transaction, JPA, 이벤트 저장 차이가 결과에 섞일 수 있다.
Blocking `FOR UPDATE`와의 직접 비교가 필요해지면 DB Lock 전략만 분리한 별도 Microbenchmark로
진행한다.

### 4.5 성능 합격 기준

첫 작업에서는 절대 TPS나 p99를 자동 실패 기준으로 두지 않는다.

| 방식 | 장점 | 위험 | 결정 |
| --- | --- | --- | --- |
| 절대 수치 Gate | 회귀를 즉시 차단 | 장비·Docker 상태에 따라 Flaky | 최초 기준선에서는 사용하지 않음 |
| 고정 환경 기준선 수집 | 재현 조건과 변화 추세를 확보 | 수동 비교 필요 | 이번 작업에 적용 |
| 고정 CI Runner 상대 회귀 Gate | 장기 회귀 탐지 가능 | 전용 Runner와 Baseline 관리 필요 | 후속 확장 |

자동 실패는 정합성 위반, 예외, Timeout, Deadlock, Queue 미소진에만 적용한다. 성능 수치는 환경
Fingerprint와 함께 결과 문서에 기록한다.

## 5. 핵심 규칙과 불변식

1. Benchmark의 각 성공 응답은 서로 다른 Job ID를 가져야 한다.
2. 준비된 모든 Job은 실행 종료 후 정확히 한 번 Claim돼야 한다.
3. 성공 Claim 수는 초기 Job 수와 같아야 한다.
4. 최종 `PENDING` 수는 0이고 `PROCESSING` 수는 초기 Job 수와 같아야 한다.
5. 모든 `PROCESSING` Job에는 Worker, Claim Token, `locked_at`, `lock_expires_at`이 있어야 한다.
6. Claim Token은 Job별로 중복되지 않아야 한다.
7. Job마다 `LOCKED` 이벤트가 정확히 한 건 있어야 한다.
8. Worker Thread에서 발생한 예외를 삼키지 않고 Profile 결과에 포함해야 한다.
9. Worker가 받는 빈 결과는 Queue 종료 신호이며 오류나 성공 지연 표본에 포함하지 않는다.
10. 각 Service 호출은 Spring Proxy가 만든 독립 Transaction이어야 한다.
11. 측정 시작 전에 모든 Worker Thread가 준비돼야 한다.
12. Warm-up 결과는 성능 통계에서 제외한다.
13. Profile 사이에는 Job, 이벤트, Worker 데이터를 초기화하고 동일한 분포로 다시 Seed한다.
14. 측정 중 Claim Token, DB Password, JWT Secret을 로그나 결과 문서에 남기지 않는다.
15. 성능 비교는 같은 Host, Docker 자원, DB Image, JVM, Pool 크기, 데이터 크기로 실행한 결과끼리만 한다.

## 6. 전체 동작 흐름

1. Benchmark 전용 Test Schema로 Spring Context를 시작한다.
2. OpenSQL, PostgreSQL, pgvector, Java, OS, CPU, JVM Heap, Docker 환경 정보를 수집한다.
3. Benchmark Worker Connection의 PostgreSQL Application Name을 고정한다.
4. 별도 Monitoring Connection을 열어 Worker용 Hikari Pool과 분리한다.
5. Warm-up용 Worker와 Job을 Seed한다.
6. 모든 Warm-up Worker Thread를 준비시킨 뒤 동시에 시작한다.
7. Worker가 실제 Claim Service를 Queue가 빌 때까지 반복 호출한다.
8. Warm-up 결과의 정합성만 확인하고 시간 측정값은 폐기한다.
9. 첫 측정 Profile의 Worker와 Job 데이터를 동일한 분포로 다시 Seed한다.
10. Hikari와 PostgreSQL 대기 상태 Sampler를 시작한다.
11. 모든 Worker Thread가 준비되면 공통 Start Gate를 연다.
12. 각 Worker가 성공 Claim의 시작·종료 시각을 자신의 Thread 로컬 결과에 기록한다.
13. 각 Worker는 빈 결과를 받으면 정상 종료한다.
14. 공통 Deadline 안에 모든 Future 결과를 수집한다.
15. Sampler를 중지하고 Pool 대기와 DB Lock 대기 관찰값을 확정한다.
16. 응답 Job 집합과 최종 DB 상태·소유권·이벤트를 비교한다.
17. Profile의 TPS, p50, p95, p99, 최대 지연, Worker 분포를 계산한다.
18. 같은 Profile을 정해진 횟수만큼 반복한다.
19. Worker 동시성 수준을 바꿔 모든 Profile을 같은 순서로 실행한다.
20. Profile별 반복 결과와 전체 중앙값을 기계 판독 가능한 단일 접두사 로그로 출력한다.
21. 모든 Profile이 끝나면 Schema를 삭제한다. 명시적 보존 설정이 있으면 수동 확인을 위해 유지한다.
22. 실행 환경, 명령, 원시 결과 위치, 집계값, 해석과 한계를 테스트 결과 문서에 기록한다.

## 7. 계층별 구현 설계

### 7.1 Benchmark Test

`EmbeddingJobClaimPerformanceBenchmark` 한 클래스가 Benchmark Orchestration과 측정 경계를 담당한다.

- Spring이 관리하는 실제 Claim Service 호출
- 전용 Schema와 Benchmark 전용 동적 Test Property 적용
- Worker·문서·문서 버전·Job Seed
- Profile별 데이터 초기화
- Warm-up과 측정 실행
- Worker Thread Gate와 공통 Deadline
- Thread별 지연 표본 수집
- Hikari와 PostgreSQL 대기 상태 Sampling
- 성능 집계와 정합성 Assertion
- 결과 로그 출력과 Schema 정리

Profile, Worker 결과, Latency 요약, 대기 상태 요약은 Test 클래스 내부의 중첩 Record로 둔다. 다른
테스트가 재사용하지 않는 값을 별도 Production 또는 Test 공용 추상화로 만들지 않는다.

### 7.2 Production Service와 Repository

변경하지 않는다. Benchmark는 기존 Bean을 주입받아 현재 Production 경로를 그대로 측정한다.

### 7.3 Gradle

기존 `benchmarkTest`는 모든 Benchmark를 실행하는 용도로 유지한다. Job Claim 성능 측정만 독립적으로
실행할 수 있도록 `claimPerformanceTest` Verification Task를 추가한다.

Test 클래스에는 공통 `benchmark` Tag와 전용 `claim-performance` Tag를 함께 둔다.

- 기본 `test`와 `build`: 기존 `benchmark` 제외 규칙으로 계속 제외
- `benchmarkTest`: 기존 Benchmark와 함께 실행
- `claimPerformanceTest`: Job Claim 성능 Benchmark만 실행

### 7.4 결과 문서

실제 실행 명령, 재현 환경, 측정값, DB 확인, 한계와 해석은 `docs/test-results/`에 기록한다.
설계 문서에는 실행 전 계획만 남기고 측정값을 추가하지 않는다.

## 8. 파일 변경 계획

| 구분 | 경로 | 책임과 변경 이유 |
| --- | --- | --- |
| CREATE | `docs/design/embedding-job-claim-performance-benchmark.md` | 구현 전 범위, 측정 방법, 불변식, 파일 계획과 완료 기준을 확정 |
| MODIFY | `build.gradle` | `claim-performance` Tag 전용 `claimPerformanceTest` Task 등록 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/embedding/benchmark/EmbeddingJobClaimPerformanceBenchmark.java` | 실제 Claim Service 기반 다중 Worker 성능·경합 Benchmark와 정합성 검증 |
| CREATE | `docs/test-results/gimin-#61-embedding-job-claim-performance.md` | 실행 환경, 명령, 반복 결과, DB 관찰값, 해석, 재현·정리 절차 기록 |

다음 파일은 변경하지 않는다.

- `EmbeddingJobClaimService`
- `EmbeddingJobRepository`
- `EmbeddingJob`
- `IndexingJobAdminController`
- `application.yml`
- `application-test.yml`
- Flyway Migration
- 기존 Claim 정합성 통합 테스트와 결과 문서

## 9. DB와 데이터 변경

### 9.1 Schema

Flyway Migration은 추가하지 않는다. Benchmark가 `docgrid_embedding_job_claim_performance_test` 전용
Schema를 사용하고 기존 Migration과 Seed를 그대로 적용한다.

### 9.2 Profile 데이터

각 Profile은 동일한 합성 데이터 분포를 사용한다.

| 항목 | 기본값 |
| --- | ---: |
| Warm-up Job | 500 |
| 측정 Job | Profile당 5,000 |
| 측정 반복 | Profile당 5회 |
| Worker 동시성 | 1, 5, 10, 20, 40 |
| Worker Hikari Pool | 20 |
| PostgreSQL 상태 Sampling 주기 | 25ms |

Job priority는 0부터 4까지 결정적으로 분산하고, 생성 시각은 동일 priority 안에서도 순서가 명확하도록
서로 다른 값을 사용한다. Profile마다 같은 분포를 재생성해 Queue 구성 차이가 측정에 섞이지 않게 한다.

Worker 수가 Pool 크기보다 큰 Profile은 Connection Pool Backpressure가 p95와 p99에 주는 영향을
관찰하기 위한 것이다. Worker 1개 Profile은 경합이 거의 없는 기준선 역할을 한다.

### 9.3 초기화와 정리

- Profile 전환 시 이벤트, Job, Worker 및 연결된 Test 데이터만 FK 역순으로 초기화
- Migration과 Seed가 만든 기준 데이터는 유지
- 각 반복 실행의 Job ID 집합을 별도로 보존해 응답 집합과 비교
- 기본 종료 시 Test Schema 삭제
- `KEEP_CLAIM_PERFORMANCE_SCHEMA`가 명시된 경우 마지막 Profile 데이터를 수동 확인용으로 보존

### 9.4 Rollback

Production Schema가 바뀌지 않으므로 DB Rollback은 없다. Benchmark 실행 중 실패하면 전용 Schema를
삭제해 원복한다. 보존 설정을 사용한 경우 정리 절차를 결과 문서에 명시한다.

## 10. API와 오류 계약

### 10.1 API

새 API와 응답 변경은 없다. Benchmark는 HTTP Endpoint가 아닌 Claim Service를 호출한다.

### 10.2 오류

새로운 Application ErrorCode는 없다. Benchmark에서 다음 결과는 Test 실패로 처리한다.

- Worker Thread의 예상하지 못한 예외
- `WORKER_NOT_FOUND` 또는 `WORKER_NOT_AVAILABLE`
- Job 수보다 많은 성공 Claim
- 중복 Job ID 또는 중복 Claim Token
- Queue 미소진
- 소유권 필드 또는 `LOCKED` 이벤트 누락
- Future 또는 Profile Deadline 초과
- PostgreSQL Deadlock 증가
- Monitoring Connection 생성 실패

Queue가 소진된 뒤 반환되는 빈 결과는 정상 종료다. 빈 결과 호출 지연은 성공 Claim latency 분포와
분리해 집계한다.

## 11. 트랜잭션·동시성·멱등성

### 11.1 Transaction

Test 메서드 자체에는 Transaction을 적용하지 않는다. 각 Worker Thread가 Spring Proxy를 통해 Claim
Service를 호출할 때마다 독립 Transaction이 시작되고 Commit돼야 실제 Worker 실행과 같은 경계를
측정할 수 있다.

측정 시간은 Service Proxy 호출 직전부터 정상 반환 직후까지로 잡는다. 따라서 Connection 획득,
Transaction 시작, DB Query, Entity 변경, 이벤트 저장, Commit 비용이 모두 포함된다.

### 11.2 동시 시작

Executor Thread 수는 해당 Profile의 Worker 수와 같게 한다. 모든 Task가 준비된 뒤 공통 Start Gate를
열어 일부 Worker가 먼저 Queue를 소진하는 순차 실행을 방지한다.

Future 대기는 Profile 전체가 공유하는 Deadline을 사용한다. Future마다 전체 Timeout을 새로 부여해
실패 시 대기 시간이 Worker 수만큼 누적되지 않게 한다.

### 11.3 측정 오버헤드 제한

각 Worker는 자신의 Local List에 latency를 기록하고 종료 후 병합한다. 모든 성공 호출이 하나의
Synchronized Collection에 기록되면서 Benchmark 자체의 Lock 경합이 생기지 않게 한다.

PostgreSQL Monitoring은 Worker Hikari Pool 밖의 전용 Connection 한 개를 사용한다. Sampler Connection
때문에 Worker가 사용할 수 있는 Hikari Connection 수가 줄어들지 않게 한다.

### 11.4 멱등성과 반복

각 Profile은 독립 데이터를 사용하고 이전 반복의 Job을 재사용하지 않는다. Benchmark 재실행도 전용
Schema 초기화부터 시작하므로 이전 결과가 다음 실행의 Queue에 섞이지 않는다.

## 12. 기술·설정·외부 연결

### 12.1 기술

| 범주 | 선택 |
| --- | --- |
| Test Framework | 기존 JUnit 5와 Spring Boot Test |
| 실제 Use Case | Spring이 관리하는 `EmbeddingJobClaimService` |
| DB 준비·검증 | 기존 JdbcTemplate |
| 동시 실행 | Java ExecutorService, CountDownLatch, Future |
| 시간 측정 | 단조 증가 시간 기준 |
| Connection Pool 관찰 | 기존 Hikari Pool MXBean |
| DB Lock 관찰 | PostgreSQL `pg_stat_activity`, `pg_locks`, `pg_stat_database` |
| 결과 출력 | JUnit XML/HTML과 기계 판독 가능한 집계 로그 |
| 추가 Library | 추가 없음 |

### 12.2 Test 설정

- Benchmark Schema: `docgrid_embedding_job_claim_performance_test`
- Worker Connection Application Name: Claim 성능 Benchmark 전용 이름
- Worker Dead Threshold: 전체 Benchmark 시간보다 충분히 길게 설정
- Hikari Maximum/Minimum Pool: 측정 Profile에서 20으로 고정
- Hikari Connection Timeout: Connection 대기를 오류로 오판하지 않도록 기존 동시성 Test 수준 사용
- 전체 Profile과 Future에 교착 방지용 Safety Timeout 적용

`application.yml`과 `application-test.yml`은 변경하지 않는다. Benchmark 전용 값은 Test 클래스의
동적 Property로 제한한다.

### 12.3 환경변수

기존 Test DB 연결과 JWT 환경변수를 재사용한다. 새로운 Secret은 없다.

선택적으로 마지막 Schema를 보존하는 `KEEP_CLAIM_PERFORMANCE_SCHEMA`만 추가한다. 이 값은 운영 설정이
아니며 Benchmark 실행 편의를 위한 Test 전용 환경변수다.

### 12.4 외부 연결

- 필수: 전용 OpenSQL 14.6과 pgvector 0.8.0 컨테이너
- 불필요: MinIO
- 불필요: Embedding Server
- 불필요: 외부 Message Broker
- 불필요: 외부 Monitoring Backend

Benchmark 시작 전 실제 대상 DB에 연결한 상태 확인과 Vector Extension 확인을 수행한다. 단순 Server
준비 신호만으로 실행을 시작하지 않는다.

### 12.5 배포 영향

추가 없음. Benchmark Test와 Gradle Task는 기본 Build에서 제외되며 Production Artifact와 Runtime
설정에 영향을 주지 않는다.

## 13. 보안과 관측성

### 13.1 보안

- HTTP 계층을 호출하지 않으므로 ADMIN 인가 성능은 측정하지 않는다.
- DB Password와 JWT Secret은 환경변수로만 주입한다.
- Claim Token 원문은 출력하지 않는다.
- Worker ID와 Job ID는 정합성 검증에 사용하되 결과 문서에는 집계값만 기록한다.
- Test Schema 보존은 명시적 설정이 있을 때만 허용한다.

### 13.2 환경 Fingerprint

결과에는 다음 정보를 반드시 기록한다.

- 실행 날짜와 Commit Hash
- Host OS와 Architecture
- CPU Model과 Core 수
- Docker CPU와 Memory 할당
- OpenSQL과 PostgreSQL Version
- pgvector Version
- Java Version과 JVM Max Heap
- Spring Boot Version
- Hikari Pool 크기와 Connection Timeout
- Worker 수, Job 수, 반복 수, Sampling 주기
- DB SSL Mode와 Container Platform

### 13.3 성능 Metric

Profile과 반복별로 다음 지표를 출력한다.

- 성공 Claim 수와 오류 수
- Queue 소진 Wall Clock
- Claims per Second
- 성공 Claim latency p50, p95, p99, max
- 빈 Queue 종료 호출 수와 latency
- Worker별 Claim 수의 min, median, max
- Hikari active Connection 최대값
- Hikari Connection 대기 Thread 최대값과 대기 관찰 횟수
- PostgreSQL Lock 대기 Session 최대값과 대기 관찰 횟수
- Commit, Rollback, Deadlock 증감
- 최종 PENDING, PROCESSING, 불완전 소유권, `LOCKED` 이벤트 수

PostgreSQL Sampling은 짧은 Lock 대기를 놓칠 수 있으므로 정확한 누적 Lock 대기 시간이 아니라
관찰된 대기 횟수와 최대 동시 대기 수로 표현한다.

`pg_stat_database`의 Commit 증분에는 Claim Transaction뿐 아니라 Monitoring Connection과 Backend 통계
경계를 확정하기 위한 조회 Transaction도 포함된다. 따라서 완료된 Claim 호출 Transaction 수와 DB 전체
Commit 증분을 별도 필드로 출력하고, 후자는 정합성 합격 기준이 아닌 관찰값으로만 사용한다.

## 14. 테스트 설계

### 14.1 Benchmark Harness 자체 검증

- 전용 Schema 적용
- Warm-up 결과가 측정 표본에 포함되지 않음
- Worker 수와 Executor Thread 수 일치
- 모든 Worker 준비 후 Start Gate 개방
- Profile별 데이터가 독립적으로 초기화됨
- 공통 Deadline이 Timeout을 제한
- Sampler가 정상 시작·종료되고 Monitoring Connection을 반환
- Schema 보존 설정이 없으면 종료 후 정리

### 14.2 Profile 정합성

각 성능 Profile에서도 기존 Claim 불변식을 다시 검증한다.

- 성공 응답 수와 Seed Job 수 일치
- 응답 Job ID 중복과 누락 없음
- 최종 `PENDING` 0
- 최종 `PROCESSING` 수와 Seed Job 수 일치
- 불완전 소유권 0
- 중복 Claim Token 0
- `LOCKED` 이벤트 수와 Job 수 일치
- Job별 이벤트 중복과 누락 0
- Worker Thread 예외 0
- Deadlock 증가 0

### 14.3 성능 Profile

| Profile | Worker | Pool | 목적 |
| --- | ---: | ---: | --- |
| 기준선 | 1 | 20 | 경합이 거의 없는 단일 소비자 처리량 |
| 낮은 동시성 | 5 | 20 | 병렬 처리 초기 확장 |
| 중간 동시성 | 10 | 20 | Pool 절반 사용 |
| Pool 일치 | 20 | 20 | DB Connection 최대 병렬도 |
| Pool 초과 | 40 | 20 | Connection Backpressure와 Tail Latency |

각 Profile을 5회 실행하고 개별 결과와 반복 중앙값을 모두 남긴다. Profile 순서는 고정하고, Warm-up 뒤
동일한 데이터 생성 절차를 사용한다.

### 14.4 자동 판정과 수동 해석

자동 판정:

- 정합성
- 예외 없음
- Queue 완전 소진
- Deadlock 없음
- Safety Timeout 안에 종료

수동 해석:

- Worker 증가에 따른 TPS 증가율
- p95와 p99 증가 지점
- Hikari 대기 발생 시작점
- DB Lock 대기 관찰 여부
- Worker 20과 40 사이의 처리량 대비 Tail Latency
- PostgreSQL Queue가 현재 예상 규모에 충분한지

### 14.5 회귀 검증

- 기존 Claim 단위·Controller·통합 테스트
- 기존 다중 Worker 정합성 전용 Task
- 신규 성능 전용 Task
- 기본 전체 Build

신규 Benchmark는 기본 Build에서 제외하되, 구현 Pull Request에서는 별도로 실행하고 결과를 기록한다.

## 15. 구현 순서

1. 실제 이슈를 만들고 Test 작업으로 분류한다.
2. `build.gradle`에 독립 실행 Task와 전용 Tag를 추가한다.
3. Benchmark Test 클래스의 전용 Schema와 동적 Property 경계를 만든다.
4. 결정적 Worker·Job Seed와 Profile 초기화 흐름을 만든다.
5. Warm-up과 실제 측정 실행을 분리한다.
6. Worker Gate, Thread 로컬 latency, 공통 Deadline을 구현한다.
7. Hikari와 PostgreSQL 대기 상태 Sampler를 구현한다.
8. Profile별 TPS와 percentile 집계를 구현한다.
9. 기존 Claim 소유권·이벤트 정합성 Assertion을 재사용 가능한 Test 내부 Helper로 구성한다.
10. 기계 판독 가능한 결과 로그와 환경 Fingerprint 출력을 추가한다.
11. 작은 Profile로 Harness와 정리 동작을 확인한다.
12. 고정 환경에서 전체 Profile을 실행한다.
13. 기존 Claim 정합성 Test와 전체 Build를 실행한다.
14. 실제 이슈 번호가 포함된 테스트 결과·재현 문서를 작성한다.

## 16. 완료 기준

- Production Java, Migration, Application 설정이 변경되지 않는다.
- `claimPerformanceTest`로 신규 Benchmark만 독립 실행할 수 있다.
- 기본 `test`와 `build`에는 신규 Benchmark가 포함되지 않는다.
- Warm-up과 측정 Profile이 분리된다.
- Worker 1, 5, 10, 20, 40 Profile이 각각 5회 완료된다.
- 각 Profile에서 Job 5,000개가 중복과 누락 없이 Claim된다.
- 모든 Profile의 소유권과 `LOCKED` 이벤트 정합성이 통과한다.
- Worker Thread 예외, Timeout, Deadlock이 없다.
- TPS, p50, p95, p99, 최대 지연이 Profile별로 출력된다.
- Hikari와 PostgreSQL 대기 관찰값이 출력된다.
- 환경 Fingerprint가 측정값과 함께 기록된다.
- 동일 고정 환경에서 전체 Benchmark를 다시 실행할 수 있다.
- 실행 결과, 원시 Report 위치, DB 확인, 한계와 해석이 `docs/test-results/`에 기록된다.
- 기존 Claim 정합성 전용 Task와 전체 Build가 통과한다.

## 17. 후속 작업 호환성

### 17.1 Worker 자동 Polling

측정된 Worker 동시성별 TPS와 Tail Latency는 Poller의 Executor 크기와 한 번에 확보할 실행 Slot 수를
정하는 근거가 된다. Worker Thread를 Hikari Pool보다 무조건 크게 잡지 않고, 실제 처리량 증가가
멈추는 지점을 기준으로 설정한다.

### 17.2 Lease 길이

이번 Benchmark는 Claim Transaction 시간만 측정하므로 전체 인덱싱 Lease 길이를 직접 결정하지 않는다.
파싱, Chunking, Embedding이 연결된 뒤 전체 처리 p95 또는 p99를 별도로 측정해 Lease와 연장 주기를
정해야 한다.

### 17.3 재시도와 만료 복구

재시도 대기 Job과 만료 Lease 복구가 추가되면 Queue 후보 조건과 경합 양상이 달라진다. 해당 기능
도입 후 다음 Profile을 별도로 추가한다.

- 예약 시간이 도달한 재시도 Job과 신규 Job 혼합 Queue
- 만료 Job 회수와 신규 Claim의 동시 실행
- 과거 Claim Token 요청과 재Claim 경쟁

### 17.4 Message Broker 전환 판단

다음 신호가 고정 환경에서 반복적으로 확인될 때 PostgreSQL Queue 한계와 Message Broker 대안을
별도 설계한다.

- Worker 증가에도 TPS가 더 이상 증가하지 않음
- p95 또는 p99가 요구 지연을 지속적으로 초과
- Connection Pool 대기가 대부분의 호출에서 발생
- DB Lock 대기가 지속적으로 관찰됨
- Claim Transaction이 다른 주요 DB Workload에 영향을 줌

이번 작업은 전환을 결정하지 않고 판단에 필요한 첫 기준선을 만든다.

## 구현 인계

- 기능명: Embedding Job Claim 처리량·경합 성능 Benchmark
- 권장 작업 유형: Test
- 예상 브랜치 유형: 실제 이슈 생성 후 `test/<이슈번호>`
- 구현 전 차단 결정: 없음
- 성능 자동 Gate: 고정 CI Runner와 반복 기준선이 생길 때까지 보류
