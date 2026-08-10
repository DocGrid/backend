# 전체 인덱싱 Queue 적체·DB Pool Backpressure Benchmark 결과

- 관련 이슈: [#141](https://github.com/DocGrid/backend/issues/141)
- 설계: [Worker Queue 적체·DB Pool Backpressure Benchmark 설계](../design/gimin-%23141-worker-queue-backpressure-benchmark.md)
- 측정일: 2026-08-11
- 결론: Hikari Pool 포화와 connection waiting은 16문서 Profile부터 관측됐고, 128문서 범위에서는 붕괴가 발생하지 않았다.

## 1. 측정 목적

기존 전체 인덱싱 처리량 측정은 16·32문서의 Queue 지연을 확인했지만 HTTP 업로드와 자동 Worker가
공유하는 DB Connection Pool의 포화 여부를 관찰하지 않았다. 이번 측정은 문서 수와 업로드 동시성을
함께 높이며 다음 세 경계를 구분한다.

1. active connection이 maximum-pool-size에 처음 도달하는 Pool 포화
2. Hikari `threadsAwaitingConnection`이 발생하는 DB Pool Backpressure
3. 업로드 실패, Job 실패 또는 제한 시간 내 Queue 미소진이 발생하는 붕괴

## 2. 실행 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 17.8 |
| pgvector | 0.8.1 |
| Embedding Model | `BAAI/bge-m3` |
| Embedding Batch Size | 32 |
| Vector 차원 | 1024 |
| Hikari maximum-pool-size | 4 |
| Hikari connection timeout | 30,000ms |
| Worker 실행 Slot | 8 |
| Queue sampling 간격 | 25ms |
| 문서 크기 | 800자, 문서당 1 Chunk |
| 반복 | Profile별 2회 |
| 외부 저장소 | 실제 MinIO |

측정 문서를 800자로 제한한 이유는 CPU 기반 BGE-M3보다 HTTP 접수와 DB Connection 경쟁을
상대적으로 크게 만들어 DB Pool 압력 경계를 관찰하기 위해서다. 따라서 기존 6,400자 처리량 수치와
직접 비교하지 않는다.

## 3. 실행 명령

### 3.1 스모크 검증

```bash
DB_SSLMODE=disable ./gradlew workerQueueBackpressureTest \
  -Dworker.queue.backpressure.profiles=4x2 \
  -Dworker.queue.backpressure.repetitions=1 \
  -Dworker.queue.backpressure.warm-up-documents=1 \
  -Dworker.queue.backpressure.worker-slots=4 \
  -Dworker.queue.backpressure.output=build/reports/worker-queue-backpressure/smoke.json
```

결과: `BUILD SUCCESSFUL in 10s`

### 3.2 전체 측정

```bash
DB_SSLMODE=disable ./gradlew workerQueueBackpressureTest
```

결과: `BUILD SUCCESSFUL in 3m 29s`

로컬 환경에는 원격 DB용 SSL 설정이 존재했지만 Docker PostgreSQL은 SSL을 제공하지 않았다. 첫 실행은
`The server does not support SSL`로 Spring Context 초기화 전에 실패했고, 저장소나 비밀 설정을
수정하지 않고 두 성공 실행에만 `DB_SSLMODE=disable`을 명시했다.

### 3.3 Swagger UI 수동 검증

- 상태: 미실행
- 사유: 이 Benchmark는 운영 HTTP Endpoint가 아니라 실제 HTTP 업로드 API를 내부에서 호출하는 전용
  Gradle Test Task다. Swagger UI에서 실행할 계약이 없으며, 자동화 실행과 DB 정합성 질의로 검증한다.

## 4. 측정 결과

아래 값은 Profile별 2회 결과의 중앙값이다. `max active`와 `max waiting`은 두 반복 중 최댓값이다.

| Profile | 상태 | 처리량 (docs/min) | 전체 시간 (s) | Upload p95 (ms) | Queue wait p95 (ms) | Queue AUC (document·s) | 평균 Queue | max active | max waiting |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 16문서·4업로더 | `POOL_BACKPRESSURED` | 163.000 | 5.899 | 24.038 | 3,073.010 | 66.087 | 11.191 | 4 | 6 |
| 32문서·8업로더 | `POOL_BACKPRESSURED` | 149.266 | 12.874 | 37.815 | 9,964.056 | 250.325 | 19.428 | 4 | 10 |
| 64문서·16업로더 | `POOL_BACKPRESSURED` | 142.791 | 26.910 | 100.052 | 23,777.526 | 946.828 | 35.177 | 4 | 18 |
| 128문서·32업로더 | `POOL_BACKPRESSURED` | 142.054 | 54.099 | 186.350 | 49,201.731 | 3,673.122 | 67.828 | 4 | 33 |

### 4.1 Pool sample 비율

| Profile | active=4 sample 비율 | waiting>0 sample 비율 | Upload 실패 | FAILED Job | 미완료 Job |
|---|---:|---:|---:|---:|---:|
| 16문서·4업로더 | 2.039% | 1.778% | 0 | 0 | 0 |
| 32문서·8업로더 | 1.141% | 1.141% | 0 | 0 | 0 |
| 64문서·16업로더 | 1.527% | 1.309% | 0 | 0 | 0 |
| 128문서·32업로더 | 1.171% | 1.115% | 0 | 0 | 0 |

### 4.2 경계 판정

| 경계 | 최초 관측 Profile |
|---|---|
| Hikari Pool 포화 | 16문서·4업로더 |
| Connection Pool Backpressure | 16문서·4업로더 |
| 붕괴 | 128문서 측정 범위 내 미관측 |

## 5. 해석

### 5.1 확인된 사실

- 모든 Profile에서 active connection이 4개 Pool 전체를 사용했다.
- 첫 16문서 Profile부터 connection waiting이 관측됐다.
- 동시 업로더를 4개에서 32개로 늘리자 최대 waiting thread가 6개에서 33개로 증가했다.
- 처리량은 64문서부터 약 142문서/분으로 더 이상 증가하지 않았다.
- Queue wait p95는 부하 증가에 따라 3.07초, 9.96초, 23.78초, 49.20초로 증가했다.
- Queue AUC는 66.087에서 3,673.122 document·second로 약 55.6배 증가했다.
- 8회 실행의 480문서가 모두 `INDEXED`됐고 업로드 실패, FAILED Job, 미완료 Job은 없었다.

### 5.2 추론

Pool waiting은 발생했지만 waiting sample 비율은 모든 Profile에서 2% 미만이었다. Upload p95도
128문서에서 약 186ms였다. 따라서 이 환경에서는 DB Connection 경쟁이 접수 단계의 짧은
Backpressure로 나타났지만 30초 connection timeout이나 요청 실패까지 이어지지는 않은 것으로 보인다.

반면 고부하 처리량은 약 142문서/분에서 정체되고 Queue wait와 Queue AUC가 계속 증가했다. 앞선 Worker 수평
확장 측정에서 CPU 기반 BGE-M3 포화가 확인된 점을 함께 보면, 현재 전체 처리량의 지속 병목은 DB
Pool보다 Embedding 처리 구간이고 DB Pool 포화는 주로 동시 접수 순간에 발생했다는 해석이 가능하다.

## 6. 정합성 검증

각 성공 문서에 대해 다음을 실제 DB에서 검증했다.

- Job, Document, Document Version이 `INDEXED`
- 현재 검색 Version 전환 완료
- Job당 `SUCCESS` Attempt 1개와 retry count 0
- `LOCKED → PARSE_STARTED → CHUNKED → EMBEDDING_STARTED → INDEXED` Event 순서
- Chunk 수와 Embedding 수 일치
- 중복 `chunk_id` 없음
- 모든 Vector의 `vector_dims(vector) = 1024`
- Profile 종료 뒤 `PENDING`·`PROCESSING` Job과 활성 Worker Slot 0

## 7. 결론과 운영 판단

이 장비와 Workload에서 안전하게 말할 수 있는 결론은 다음과 같다.

> 4개 Hikari Connection은 16문서·4업로더부터 포화되고 Connection 대기가 발생한다. 그러나
> 128문서·32업로더까지는 모든 문서가 완료되어 붕괴점은 관측되지 않았다. 동시 부하가 늘어도
> 고부하 처리량은 약 142문서/분에서 정체되고 대기 시간만 증가하므로 Queue depth와 oldest pending age를
> 운영 지표로 두는 것이 필요하다.

이 결과만으로 요청 거절 임계값을 도입하지 않는다. Admission Control을 추가하려면 운영 SLO,
허용 Queue 대기 시간, 실제 문서 크기 분포와 배포 장비에서 별도의 결정을 내려야 한다.

## 8. 재현 결과 파일

- 스모크: `build/reports/worker-queue-backpressure/smoke.json`
- 전체: `build/reports/worker-queue-backpressure/worker-queue-backpressure.json`

`build/` 결과는 Git에 커밋하지 않으며, 재현 가능한 요약과 판정 근거만 이 문서에 보존한다.
