# Worker 수·실행 Slot별 전체 인덱싱 수평 확장 Benchmark 결과

## 1. 목적

[Worker 수평 확장 Benchmark 설계](../design/gimin-%23138-worker-horizontal-scaling-benchmark.md)에 따라
실제 PostgreSQL, MinIO와 BGE-M3를 사용하는 자동 인덱싱 Pipeline에서 Worker 수와 Worker별 실행
Slot 수의 영향을 비교했다.

이번 결과는 다음 두 질문에 답한다.

1. 여러 Worker Context가 같은 PENDING Job Queue를 실제로 나눠 처리하는가?
2. Worker와 실행 Slot을 늘렸을 때 전체 처리량과 Queue·처리 지연이 어떻게 변하는가?

## 2. 실행 환경

| 항목 | 값 |
|---|---|
| 실행 일자 | 2026-08-10 |
| OS | macOS aarch64 |
| 사용 가능 CPU | 10 |
| PostgreSQL | 17.8 |
| pgvector | 0.8.1 |
| Spring Boot | 3.5.16 |
| Embedding Model | `BAAI/bge-m3` |
| Embedding 차원 | 1024 |
| Embedding Batch Size | 32 |
| 문서 형식 | TXT |
| 문서 크기 | 6,400자 |
| Profile별 문서 수 | 16 |
| 문서별 Chunk·Embedding 수 | 8·8 |
| 반복 수 | 2 |
| 예열 문서 수 | 2 |
| HTTP Uploader Thread | 8 |

모든 Worker는 같은 Mac, PostgreSQL Schema, MinIO Bucket과 CPU BGE-M3 Container를 공유했다.
Worker Context 기동·Flyway 검증·종료 시간은 처리량에서 제외했다.

## 3. 실행 명령

```bash
docker compose up -d postgres minio embedding-server
DB_SSLMODE=disable ./gradlew workerHorizontalScalingTest
```

Gradle 결과:

```text
BUILD SUCCESSFUL in 8m 12s
5 actionable tasks: 1 executed, 4 up-to-date
```

원시 JSON은 Git에 포함되지 않는 다음 경로에 생성했다.

```text
build/reports/worker-horizontal-scaling/worker-horizontal-scaling.json
```

## 4. 처리량·확장성 결과

반복 2회의 중앙값이다.

| Profile | Worker | Worker별 Slot | 전체 Slot | 문서/분 | Chunk·Embedding/초 | Speedup | Slot 효율 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `w1-s1` | 1 | 1 | 1 | 19.475 | 2.597 | 1.000x | 1.000 |
| `w1-s2` | 1 | 2 | 2 | 21.862 | 2.915 | 1.123x | 0.561 |
| `w2-s1` | 2 | 1 | 2 | 21.576 | 2.877 | 1.108x | 0.554 |
| `w2-s2` | 2 | 2 | 4 | 21.577 | 2.877 | 1.108x | 0.277 |
| `w4-s2` | 4 | 2 | 8 | 21.677 | 2.890 | 1.113x | 0.139 |

전체 Slot 1개에서 2개로 늘렸을 때 처리량은 약 11~12% 증가했다. 하지만 전체 Slot을 4개와 8개로
늘려도 처리량은 약 21.6문서/분에서 더 증가하지 않았다. Slot 효율은 2 Slot 0.56, 4 Slot 0.28,
8 Slot 0.14로 감소했다.

## 5. 지연 결과

반복 2회에서 각 p95 값을 구한 뒤 그 중앙값을 사용했다.

| Profile | 전체 시간 | Queue p95 | 처리 p95 | E2E p95 |
|---|---:|---:|---:|---:|
| `w1-s1` | 49.298초 | 43.963초 | 3.391초 | 46.939초 |
| `w1-s2` | 43.914초 | 38.302초 | 5.550초 | 43.671초 |
| `w2-s1` | 44.494초 | 38.912초 | 5.647초 | 44.225초 |
| `w2-s2` | 44.493초 | 33.627초 | 11.693초 | 44.294초 |
| `w4-s2` | 44.300초 | 22.810초 | 22.741초 | 44.206초 |

Slot이 늘수록 Job이 더 빨리 Claim되어 Queue p95는 감소했다. 반면 CPU BGE-M3 요청이 동시에 늘면서
개별 Job 처리 p95가 증가했다. 두 효과가 상쇄되어 E2E p95와 전체 처리 시간은 2 Slot 이후 거의
줄지 않았다.

## 6. Worker 분배 결과

| Profile | 반복 1 | 반복 2 | 참여 Worker 중앙값 |
|---|---|---|---:|
| `w1-s1` | 16 | 16 | 1 |
| `w1-s2` | 16 | 16 | 1 |
| `w2-s1` | 8 / 8 | 8 / 8 | 2 |
| `w2-s2` | 8 / 8 | 8 / 8 | 2 |
| `w4-s2` | 4 / 4 / 4 / 4 | 4 / 4 / 4 / 4 | 4 |

모든 다중 Worker 본 측정에서 등록된 Worker가 실제 성공 Attempt를 나눠 처리했다. 이 결과는 같은
Queue에 대한 `FOR UPDATE SKIP LOCKED`, Worker 소유권과 Claim Token 경계가 독립 Worker Context에서도
유지됨을 보여준다.

## 7. 정합성 검증

모든 10개 본 측정 반복에서 다음 조건을 통과했다.

- 16개 Job 전부 `INDEXED`, Retry 0회
- Job별 `SUCCESS` Attempt 정확히 1개
- `LOCKED → PARSE_STARTED → CHUNKED → EMBEDDING_STARTED → INDEXED` Event 순서
- 실패·Lease 만료·Retry Event 없음
- Document·Version `INDEXED`와 `current_version_id` 전환
- 반복별 Chunk 128개, Embedding 128개
- Chunk별 Embedding 중복 없음
- 모든 Vector 1024차원, NaN·Infinity 없음
- PENDING·PROCESSING 잔여 Job 없음
- 다중 Worker 본 측정에서 최소 2개 Worker 참여
- 완료 대기 경계에서 모든 실행 Slot 반환

## 8. 실행 중 발견하고 보정한 경계

### 8.1 Worker Context 유형

`WebApplicationType.NONE`은 프로젝트의 `SwaggerConfig`가 웹 자동설정 Bean을 주입받는 현재 구조와
맞지 않아 순환 Bean 생성이 발생했다. 제품 코드를 수정하지 않고 실제 배포 형태와 같은 Servlet
Context를 사용하되 `server.port=0`으로 Port 충돌을 차단했다. Context 기동 시간은 측정에서 제외했다.

### 8.2 JDBC Schema 전달

`@DynamicPropertySource`는 별도 `SpringApplicationBuilder`에 자동 상속되지 않는다. Coordinator가 실제
사용 중인 Schema 포함 JDBC URL을 Worker Context에 명시적으로 전달해 API 접수와 Worker Claim이 같은
Queue를 보도록 했다. Credential은 결과와 Log에 기록하지 않았다.

### 8.3 예열과 본 측정 분리

2개 예열 문서는 Scheduler Timing에 따라 한 Worker가 모두 처리할 수 있다. 예열은 Cache·Model 준비와
정합성만 검증하고, 다중 Worker 참여 조건은 16문서 본 측정에만 적용했다.

## 9. 해석과 한계

- 같은 전체 Slot 2개에서 `w1-s2`와 `w2-s1` 처리량이 거의 같으므로 Worker Context 자체의 추가
  Overhead는 이번 규모에서 크지 않았다.
- 2 Slot 이후 처리량이 포화되고 처리 p95만 늘어난 주된 후보는 모든 Worker가 공유한 단일 CPU
  BGE-M3 Container다.
- Worker 분배와 DB 정합성은 검증했지만 이 결과를 여러 물리 Host의 Network·Container 환경 성능으로
  일반화할 수 없다.
- PostgreSQL과 MinIO도 같은 Host를 공유했으므로 BGE, DB, Storage 병목 기여도를 개별 분리하지 않았다.
- 다중 Host 또는 GPU BGE 환경에서는 같은 Profile을 다시 실행해 확장 상한을 재측정해야 한다.

## 10. 결론

자동 Worker는 2개와 4개 Context에서 같은 Queue를 실제로 균등 분담했고 모든 인덱싱·Vector 불변식을
유지했다. 현재 단일 Host CPU 환경에서는 전체 Slot 2개가 실용적인 포화 지점이며, Worker·Slot을 그보다
늘리면 Queue 대기는 줄지만 BGE 처리 대기가 증가해 최종 처리량은 약 21.6문서/분에 머물렀다.
