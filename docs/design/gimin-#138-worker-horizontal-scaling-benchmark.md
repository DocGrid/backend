# 자동 Worker 수·실행 슬롯별 전체 인덱싱 수평 확장 Benchmark 설계

## 1. 배경

단일 자동 Worker와 실행 슬롯 2개에서 실제 PostgreSQL 17, MinIO와 `BAAI/bge-m3`를 통과하는 전체
문서 인덱싱 처리량 기준선을 확보했다. 16개 문서는 분당 19.102개, 32개 문서는 분당 18.402개를
처리했고 전체 지연 증가는 실제 처리보다 Queue 대기가 지배했다.

이 기준선만으로 Worker 인스턴스를 늘렸을 때 처리량이 증가하는지, 실행 슬롯만 늘리는 것과 Worker 수를
늘리는 것이 어떻게 다른지는 판단할 수 없다. 이번 작업은 같은 Job Queue를 여러 독립 Worker Context가
경쟁해 처리하도록 구성하고 Worker 수와 Worker별 실행 슬롯 조합에 따른 처리량·지연·분산도를 측정한다.

## 2. 목표

1. Worker 1·2·4개가 실제 `FOR UPDATE SKIP LOCKED` Claim으로 Job을 나눠 처리하는지 검증한다.
2. Worker별 실행 슬롯 1·2개가 문서·Chunk·Embedding 처리량과 Queue 지연에 주는 영향을 비교한다.
3. `1 Worker × 1 Slot` 기준 Speedup과 전체 Slot 기준 Scaling Efficiency를 계산한다.
4. 처리량이 증가하더라도 Attempt·Event·Chunk·Embedding·Vector 불변식이 유지되는지 확인한다.
5. 단일 Host CPU BGE-M3가 수평 확장의 공통 병목이 되는 지점을 측정 결과로 설명한다.

## 3. 범위

### 3.1 포함

- 실제 인증과 Multipart HTTP 문서 업로드
- 실제 MinIO Object 저장·읽기
- 실제 PostgreSQL Worker 등록·Heartbeat·Claim·Attempt·Lease
- 독립 Worker별 Executor, Slot Pool, Scheduler와 Hikari Connection Pool
- 실제 TXT Parsing·Chunk 저장·BGE-M3 Batch Embedding·`vector(1024)` 저장
- Version `INDEXED`와 Document `current_version_id` 전환
- Worker별 성공 Attempt 분포
- 문서·Chunk·Embedding 처리량
- Queue·처리·전체 p50·p95·p99·최댓값
- Baseline 대비 Speedup과 Scaling Efficiency
- 전용 Gradle Task와 Git 제외 JSON 원시 결과
- 공개 가능한 실측 결과와 한계 문서

### 3.2 제외

- 여러 물리 Host·VM·Container 사이 Network 비용
- Kubernetes, Service Discovery와 Load Balancer
- 공식 Rocky Linux OpenSQL 원격 환경 재측정
- Queue 상한과 요청 거부 같은 Backpressure 정책 구현
- PDF·DOCX 형식별 Parser 성능 비교
- GPU BGE-M3 확장성
- 운영 SLO 확정

## 4. 실행 구조

Benchmark는 역할이 다른 두 종류의 Spring Context를 사용한다.

```text
Coordinator Context
  ├─ RANDOM_PORT HTTP Server
  ├─ 실제 인증·업로드 API
  ├─ Benchmark 상태 조회·결과 검증
  └─ indexing.worker.enabled=false

Worker Context 1..N
  ├─ WebApplicationType.SERVLET + server.port=0
  ├─ 고유 Worker Name·Instance ID
  ├─ 고유 Job Executor·Slot Pool
  ├─ 고유 Hikari Pool
  └─ 같은 PostgreSQL Schema·MinIO Bucket·BGE-M3 사용
```

Coordinator는 Job을 직접 Claim하지 않는다. Worker Context만 Worker Node로 등록되고 Production
`WorkerJobPollingScheduler`를 실행한다. 따라서 Worker 수가 늘어날 때 각 Context가 실제 DB Lock과
Claim Token 경계를 통과한다.

Worker Context는 프로젝트의 실제 배포 애플리케이션과 같은 Servlet 자동 설정을 사용한다. 현재
`SwaggerConfig`는 Web Application에서 제공되는 `SwaggerUiConfigProperties`를 주입받으므로, Worker만
검증하려고 `WebApplicationType.NONE`을 사용하면 해당 Bean이 자기 자신을 주입하는 순환 생성이 발생한다.
각 Context는 `server.port=0`으로 HTTP Port 충돌만 차단하고, 기동 시간은 처리량 측정에서 제외한다.

Worker Context는 한 Profile 설정 동안 유지한다. 시작·Flyway 검증·종료 시간은 처리량 측정에서 제외한다.
Profile이 끝난 뒤 모든 실행 슬롯이 반환된 것을 확인하고 Context를 역순으로 닫아 Worker를 `STOPPED`로
전환한다.

## 5. 격리 계약

- Gradle 실행마다 UUID 기반 PostgreSQL Schema와 MinIO Bucket을 생성한다.
- Schema 접미사는 PostgreSQL 식별자 63자 제한 안에서 24자로 제한한다.
- 모든 Worker Context에 동일한 전용 Schema와 Bucket을 명시한다.
- Worker Context마다 고유 `spring.application.name`, Worker Name과 Hikari Pool Name을 사용한다.
- Profile 초기화는 Worker 실행 슬롯이 모두 반환된 뒤 Job·Attempt·Event·Document·Vector Data만 지운다.
- Worker Context를 모두 닫은 뒤 이전 Profile의 `worker_nodes`를 정리한다.
- 종료 시 Benchmark가 만든 Bucket과 Schema만 삭제한다.
- DB·MinIO·JWT Credential과 접속 문자열은 JSON·Log·문서에 기록하지 않는다.

## 6. Profile 계약

기본 Profile은 Worker 수와 Worker별 Slot 수의 영향을 분리하면서 과도한 실행 조합을 피한다.

| Profile | Worker 수 | Worker별 Slot | 전체 Slot | 비교 목적 |
|---|---:|---:|---:|---|
| `w1-s1` | 1 | 1 | 1 | Baseline |
| `w1-s2` | 1 | 2 | 2 | 단일 Process 내부 Slot 확장 |
| `w2-s1` | 2 | 1 | 2 | 같은 전체 Slot에서 Worker 수 효과 |
| `w2-s2` | 2 | 2 | 4 | Worker와 Slot 동시 확장 |
| `w4-s2` | 4 | 2 | 8 | Local 확장 상한과 BGE 병목 관찰 |

기본 본 측정은 Profile마다 같은 16개 TXT 문서를 2회 처리하고, 각 Profile 시작 뒤 2개 문서로 예열한다.
문서 본문은 6,400자로 고정해 문서당 Chunk·Embedding 수가 같게 한다.

다음 System Property로 실행 범위를 조정할 수 있다.

```text
worker.horizontal.scaling.profiles
worker.horizontal.scaling.document-characters
worker.horizontal.scaling.document-count
worker.horizontal.scaling.repetitions
worker.horizontal.scaling.warm-up-documents
worker.horizontal.scaling.uploader-threads
worker.horizontal.scaling.profile-timeout-seconds
worker.horizontal.scaling.status-polling-ms
worker.horizontal.scaling.output
```

Profile 문자열은 `workerCount x slotsPerWorker` 형식의 쉼표 목록으로 받는다. Worker·Slot·문서·반복 값은
모두 1 이상이어야 하고 중복 Profile은 거부한다. Speedup 기준선을 계산할 수 있도록 사용자 지정 Profile에도
`1x1`을 반드시 포함해야 한다.

## 7. 측정 경계와 지표

### 7.1 측정 순서

1. Profile Worker Context를 순서대로 시작하고 모든 Worker ID 등록을 확인한다.
2. Warm-up 문서를 업로드하고 모두 `INDEXED`가 될 때까지 기다린다.
3. Job Data를 초기화하고 Worker가 Idle인지 확인한다.
4. 시작 시각을 기록하고 고정 Uploader Thread로 본 측정 문서를 병렬 접수한다.
5. 마지막 Upload 응답 시각을 기록한다.
6. 모든 대상 Job이 `INDEXED`이고 모든 Worker Slot이 반환될 때까지 기다린다.
7. 결과 불변식과 Worker별 처리 분포를 검증한 뒤 지표를 계산한다.
8. 반복 완료 뒤 중앙값을 기록하고 Worker Context를 닫는다.

### 7.2 처리량과 지연

| 지표 | 계산 |
|---|---|
| documents/s | 완료 문서 수 / 전체 측정 시간 |
| documents/min | documents/s × 60 |
| chunks/s | Chunk 수 / 전체 측정 시간 |
| embeddings/s | Embedding 수 / 전체 측정 시간 |
| Queue 대기 | Job `created_at` → 첫 `LOCKED` Event |
| 실제 처리 | 첫 `LOCKED` → `INDEXED` Event |
| 전체 지연 | Job `created_at` → `INDEXED` Event |
| Speedup | Profile documents/s 중앙값 / `w1-s1` 중앙값 |
| Scaling Efficiency | Speedup / Profile 전체 Slot 수 |

지연은 선형 보간 p50·p95·p99와 max를 밀리초로 기록한다. Worker 분포는 성공 Attempt의
`worker_node_id`별 Job 수를 저장한다.

`Scaling Efficiency`는 전체 Slot 수 증가에 대한 단순 효율이다. Worker Context마다 별도 Connection Pool과
Scheduler가 생기는 비용을 분리하지 않으므로 CPU·DB·BGE 자원 전체의 효율로 해석하지 않는다.

## 8. 정합성 계약

각 본 측정 반복은 다음 조건을 모두 만족해야 한다.

- 대상 Job 전부 `INDEXED`
- PENDING·PROCESSING 잔여 Job 없음
- Job별 Retry 0회, `SUCCESS` Attempt 정확히 1개
- `LOCKED → PARSE_STARTED → CHUNKED → EMBEDDING_STARTED → INDEXED` Event 순서
- 실패·Lease 만료·Retry Event 없음
- Document와 Version 전부 `INDEXED`
- `documents.current_version_id`가 측정 Version을 가리킴
- 문서별 Chunk 수와 Embedding 수 일치
- 같은 Chunk의 Embedding 중복 없음
- Vector 차원 1024, NaN·Infinity 없음
- Profile의 모든 Worker가 등록 상태를 유지함
- Worker 수가 2개 이상이고 문서 수가 Worker 수 이상이면 최소 2개 Worker가 성공 Attempt를 처리함
- 측정 종료 시 모든 Worker 실행 Slot이 반환됨

Worker 분배는 균등성을 강제하지 않는다. 단일 CPU BGE 응답 시간과 Scheduler Timing에 따라 분배 편차가
생길 수 있으므로 참여 Worker 수와 실제 처리 건수만 관찰값으로 기록한다.

## 9. 결과 판정

- 처리량과 Speedup은 자동 합격선을 두지 않고 반복 중앙값으로 관찰한다.
- 처리량이 감소해도 정합성을 만족하면 Benchmark는 통과하며 병목 근거로 기록한다.
- 다중 Worker Profile에서 실제로 하나의 Worker만 모든 Job을 처리하면 수평 확장 검증 실패로 처리한다.
- Slot 수가 늘어도 처리량이 증가하지 않으면 공유 BGE CPU, DB Connection 또는 Host Scheduling 병목 후보로
  기록한다.
- Local 동일 JVM 결과를 다중 Host 운영 성능으로 표현하지 않는다.

## 10. 실행 경계

일반 `./gradlew test`는 이 Benchmark Tag를 제외한다. 실제 Infrastructure를 사용하는 전용 Task만 실행한다.

```bash
docker compose up -d postgres minio embedding-server
DB_SSLMODE=disable ./gradlew workerHorizontalScalingTest
```

원시 JSON은 Git에 포함되지 않는 다음 경로에 생성한다.

```text
build/reports/worker-horizontal-scaling/worker-horizontal-scaling.json
```

## 11. 커밋 분할

1. `docs: #138 Worker 수평 확장 Benchmark 설계 추가`
2. `test: #138 다중 Worker 수평 확장 Benchmark 추가`
3. `build: #138 Worker 수평 확장 전용 실행 경계 추가`
4. `perf: #138 Worker 수평 확장 실측 결과 기록`

리뷰 수정은 지적된 불변식과 실행 경계만 별도 커밋으로 반영한다.

## 12. 완료 조건

- 실제 Worker 1·2·4개와 Slot 조합이 같은 PENDING Queue를 나눠 처리한다.
- 처리량·지연·Worker 분포·Speedup·Scaling Efficiency가 구조화된 JSON에 기록된다.
- 모든 Profile에서 Job·Attempt·Event·Chunk·Embedding·Vector 불변식을 검증한다.
- 일반 회귀와 실제 Infrastructure Smoke·정식 Benchmark가 통과한다.
- 측정 환경, 병목 해석과 다중 Host로 일반화할 수 없는 한계를 결과 문서에 기록한다.
