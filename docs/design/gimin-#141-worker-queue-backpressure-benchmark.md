# Worker Queue 적체·DB Pool Backpressure Benchmark 설계

- 관련 이슈: [#141](https://github.com/DocGrid/backend/issues/141)
- 작성일: 2026-08-11
- 상태: 구현 및 실측 완료

## 1. 배경

자동 Worker 전체 인덱싱 처리량 Benchmark는 16개와 32개 문서를 실제 PostgreSQL, MinIO,
BGE-M3에 통과시켜 Queue 대기와 전체 처리량을 확인했다. Worker 수평 확장 Benchmark는 실행
Slot이 2개를 넘을 때 처리량이 더 이상 증가하지 않는다는 점도 확인했다.

두 결과만으로는 HTTP 업로드와 자동 Worker가 공유하는 Hikari Connection Pool의 포화 여부를
설명할 수 없다. 문서 접수 동시성과 Queue 크기를 더 키웠을 때 다음 질문에 답할 측정값이 없다.

1. Pool의 active connection이 maximum-pool-size에 처음 도달하는 부하는 어디인가?
2. connection을 기다리는 thread가 실제로 발생하는 부하는 어디인가?
3. Queue가 계속 증가해 실패하거나 제한 시간 안에 소진되지 않는 부하는 어디인가?
4. 처리량이 정체될 때 Queue 대기와 Pool 대기 중 어느 현상이 먼저 나타나는가?

## 2. 목표와 성공 기준

이 작업은 운영 정책을 추가하는 기능 작업이 아니라 전체 인덱싱 파이프라인의 용량 경계를 찾는
Benchmark 작업이다.

- 문서 수와 동시 업로드 수가 함께 증가하는 Profile을 같은 환경에서 반복 실행한다.
- 업로드부터 `INDEXED` 전환까지 Production HTTP API와 자동 Worker를 그대로 사용한다.
- Hikari Pool과 Job Queue를 별도 monitoring connection으로 일정 간격 관찰한다.
- 처리량, 업로드 지연, Queue drain 시간, Queue depth, Pool 대기를 하나의 결과로 남긴다.
- 각 결과가 데이터 정합성을 깨뜨리지 않았는지 Chunk, Embedding, Vector, 상태 전이로 검증한다.
- 관측된 사실만으로 포화·Backpressure·붕괴 경계를 판정한다.

## 3. 제외 범위

- API Rate Limit, Queue Admission Control 또는 요청 거절 정책 구현
- 특정 Queue 크기를 운영 환경의 보편적인 한계로 단정
- PostgreSQL 또는 OpenSQL 서버 설정 튜닝
- Hikari Pool 크기의 자동 조절
- Mock Embedding으로 실제 BGE-M3 병목 제거

요청 거절 정책은 제품 요구사항과 운영 SLO가 정해진 뒤 별도 이슈에서 설계해야 한다. 이 Benchmark는
그 정책의 입력이 될 측정 근거만 만든다.

## 4. Workload 설계

### 4.1 기본 Profile

| Profile | 문서 수 | 업로드 Thread | 목적 |
|---|---:|---:|---|
| `d16-u4` | 16 | 4 | 기존 16문서 기준선과 저부하 확인 |
| `d32-u8` | 32 | 8 | 기존 32문서 Queue 측정 확장 |
| `d64-u16` | 64 | 16 | Pool 크기를 넘는 접수 동시성 확인 |
| `d128-u32` | 128 | 32 | Queue 적체와 connection 대기 확대 |

- Hikari maximum-pool-size: 기본 4
- Worker maximum-concurrency: 기본 8
- 문서 크기: 기본 800자
- 반복: 기본 2회
- sampling 간격: 기본 25ms
- Profile 제한 시간: 기본 600초

문서는 1개 Chunk가 생성되도록 작게 고정한다. 기존 6,400자 처리량 Benchmark와 직접 수치를 비교하기
위한 Workload가 아니라, CPU 기반 BGE-M3보다 HTTP 접수와 DB Connection 경쟁을 상대적으로 크게
만들어 DB Pool 경계를 관측하기 위한 Workload다. 모든 기본값은 System Property로 재실행할 수 있다.

### 4.2 실행 순서

1. 전용 Schema와 MinIO Bucket을 생성한다.
2. PostgreSQL 17, pgvector 0.8.1, BGE-M3, Hikari와 Worker 설정을 검증한다.
3. 예열 문서를 실제 전체 파이프라인에 통과시킨다.
4. Profile 데이터를 초기화하고 별도 monitoring connection과 Sampler를 시작한다.
5. Profile의 uploader thread 수로 문서를 동시에 업로드한다.
6. 접수된 Job이 모두 종착 상태가 되거나 제한 시간이 끝날 때까지 관찰한다.
7. Sampler를 중지하고 처리량·Queue·Pool 지표와 정합성 결과를 기록한다.
8. Profile별 반복 결과와 최초 포화·Backpressure·붕괴 Profile을 JSON으로 저장한다.

## 5. 관측 지표

### 5.1 Hikari Pool

Spring이 관리하는 `HikariPoolMXBean`에서 다음 값을 읽는다.

- active, idle, total connection 최댓값
- threads awaiting connection 최댓값
- active가 maximum-pool-size와 같은 sample 비율
- awaiting thread가 1 이상인 sample 비율

Sampler의 Queue SQL 때문에 Application Pool을 점유하면 측정값이 변한다. 따라서 Queue 상태 SQL은
`DriverManager`로 연 별도 read-only monitoring connection에서 실행한다.

### 5.2 Queue

각 sample 시점에 Profile 접수 이후 생성된 Job만 대상으로 다음 상태 수를 읽는다.

- `PENDING`
- `PROCESSING`
- `INDEXED`
- `FAILED`

Queue depth는 `PENDING + PROCESSING`으로 정의한다. 다음 요약값을 기록한다.

- peak pending과 peak queue depth
- 평균 queue depth
- queue depth AUC(document·second)
- upload 종료 뒤 queue drain 시간

### 5.3 처리량과 오류

- 업로드 성공·실패 수
- 업로드 p50, p95, p99, max latency
- 접수 성공 문서 중 `INDEXED`, `FAILED`, 제한 시간 내 미완료 수
- 전체 문서/분, Chunk/초, Embedding/초
- Job queue wait, processing, end-to-end p95

## 6. 판정 계약

판정은 임의의 성능 임계값 대신 직접 관측 가능한 사건으로 구성한다.

| 상태 | 판정 |
|---|---|
| `STABLE` | active가 Pool 최대치에 도달하지 않고 awaiting thread가 없으며 모두 완료 |
| `POOL_SATURATED` | active가 maximum-pool-size에 한 번 이상 도달했지만 awaiting thread는 미관측 |
| `POOL_BACKPRESSURED` | awaiting thread가 한 번 이상 관측되었고 모두 완료 |
| `COLLAPSED` | 업로드 실패, Job 실패 또는 제한 시간 내 Queue 미소진 중 하나 이상 발생 |

각 Profile은 가장 심각한 상태 하나를 가진다. 전체 결과에는 문서 수 오름차순으로 처음 관측된
`POOL_SATURATED`, `POOL_BACKPRESSURED`, `COLLAPSED` Profile을 별도로 기록한다. 최대 부하에서도
`COLLAPSED`가 없으면 `not-observed`로 기록하고 추정값을 만들지 않는다.

## 7. 정합성 검증

성능 측정이 성공하려면 접수된 모든 문서에 대해 다음 계약을 만족해야 한다. 단, 붕괴 Profile은 실패
수를 결과에 남기되 접수에 성공해 `INDEXED`가 된 문서의 데이터 정합성은 동일하게 검증한다.

- Job과 Document Version이 종착 상태다.
- 성공 Job은 Attempt 1개가 `SUCCESS`이고 검색 Version이 전환된다.
- Chunk 수와 Embedding 수가 같다.
- `chunk_id` 중복이 없다.
- 모든 Vector 차원이 1024다.
- 성공 Event 순서가 `LOCKED → PARSE_STARTED → CHUNKED → EMBEDDING_STARTED → INDEXED`다.
- 측정 종료 뒤 Worker 실행 Slot 수가 0이다.

## 8. 결과물

- `WorkerQueueBackpressureBenchmark`: 실제 전체 파이프라인 실행과 측정
- `WorkerQueueBackpressureStatistics`: Profile parsing, Queue AUC, 상태 판정의 순수 계산
- 단위 테스트: 경계값, 중복 Profile, Queue AUC, 상태 우선순위 회귀 검증
- `workerQueueBackpressureTest`: 일반 테스트와 분리된 Gradle task
- `build/reports/worker-queue-backpressure/worker-queue-backpressure.json`: 원시 결과
- `docs/test-results/gimin-#141-worker-queue-backpressure-benchmark.md`: 실측 결과와 해석

## 9. 커밋 분할

1. `docs: #141 Queue 적체 및 DB Pool Backpressure Benchmark 설계`
2. `test: #141 Queue Backpressure 통계 계약 추가`
3. `perf: #141 전체 인덱싱 Queue 및 DB Pool Benchmark 추가`
4. `build: #141 Queue Backpressure 전용 테스트 작업 추가`
5. `perf: #141 Queue 및 DB Pool 포화 실측 결과 기록`
