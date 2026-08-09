# Issue #133 자동 Worker 전체 문서 인덱싱 처리량 Benchmark 결과

## 1. 결과 요약

PostgreSQL 17.8, pgvector 0.8.1, MinIO와 실제 `BAAI/bge-m3`를 연결하고 자동 Polling Worker가
문서 업로드부터 `INDEXED` 전환까지 처리하는 전체 경로를 측정했다. 4개 문서 예열 후 16개와 32개
문서 Profile을 각각 3회 실행했다.

| 문서 수 | 반복 | 중앙 총 시간 | 문서/초 | 문서/분 | Chunk·Embedding/초 | 전체 P95 |
|---:|---:|---:|---:|---:|---:|---:|
| 16 | 3회 | 50.255초 | 0.318 | 19.102 | 2.547 | 50.127초 |
| 32 | 3회 | 104.335초 | 0.307 | 18.402 | 2.454 | 100.665초 |

Queue를 16개에서 32개로 두 배 늘렸을 때 분당 처리량은 약 3.7% 감소했다. Worker 실행 슬롯을
2개로 고정했기 때문에 처리 시간 P95는 6.768초에서 7.285초로 비슷하게 유지됐고, 전체 지연 증가는
주로 Queue 대기 P95가 43.794초에서 93.554초로 늘어난 데서 발생했다.

## 2. 공개 가능한 실행 환경

| 항목 | 값 |
|---|---|
| Database | PostgreSQL 17.8, Local Docker |
| pgvector | 0.8.1 |
| Object Storage | MinIO, Local Docker |
| Embedding Provider | `BAAI/bge-m3`, Local Docker CPU 추론 |
| Vector 차원 | 1024 |
| Embedding Batch Size | 32 |
| Worker 동시 실행 슬롯 | 2 |
| Worker Polling 주기 | 50 ms |
| 업로더 Thread | 4 |
| 문서 크기 | TXT 6,400자 |
| 문서당 Chunk·Embedding | 각각 8개 |
| Warm-up | 4개 문서 |
| 본 측정 | 16 / 32개 문서, Profile당 3회 |
| Application | Spring Boot 3.5.16, Java 17 |
| 실행 장비 | macOS `aarch64`, 가용 Processor 10개 |
| 실행 일자 | 2026-08-10 KST |

DB Host·Database 이름·Username·Password, MinIO Credential과 JWT 값은 결과에 기록하지 않았다.
이번 결과는 로컬 개발 장비의 기준선이며 공식 OpenSQL 원격 Server 성능이나 운영 SLO가 아니다.

## 3. 측정 범위

각 문서는 다음 전체 경로를 통과했다.

```text
TXT 업로드
→ MinIO 저장
→ Embedding Job PENDING
→ 자동 Worker Polling·Claim
→ Attempt 시작
→ 텍스트 Parsing·Chunk 저장
→ 실제 BGE-M3 Batch 호출
→ vector(1024) 저장
→ Version INDEXED
→ Document current_version 전환
→ Worker 실행 슬롯 반환
```

Profile마다 Benchmark 전용 Schema와 Bucket의 데이터를 초기화해 이전 실행의 Job·Chunk·Embedding이
다음 실행의 수치에 포함되지 않게 했다. Worker Node는 Application 수명주기를 유지하기 위해 Profile
사이에서 재사용했다.

## 4. 실행 방법

PostgreSQL, MinIO와 Embedding Server가 모두 건강 상태인 로컬 환경에서 실행했다.

```bash
docker compose up -d postgres minio embedding-server
DB_SSLMODE=disable ./gradlew workerIndexingThroughputTest
```

구조화 원시 결과는 Git에 포함하지 않는 다음 경로에 생성된다.

```text
build/reports/worker-indexing-throughput/worker-indexing-throughput.json
```

실환경 연결과 결과 계약을 빠르게 확인할 때는 다음 Smoke Profile을 사용했다.

```bash
DB_SSLMODE=disable ./gradlew workerIndexingThroughputTest \
  -Dworker.indexing.throughput.warm-up-documents=2 \
  -Dworker.indexing.throughput.document-counts=4 \
  -Dworker.indexing.throughput.repetitions=1 \
  -Dworker.indexing.throughput.output=build/reports/worker-indexing-throughput/smoke.json
```

Smoke 결과는 4개 문서, 32개 Chunk, 32개 Embedding을 12.461초에 처리했고 분당 19.260문서를
기록했다.

## 5. 반복별 원시 결과

| 문서 수 | 회차 | 총 시간 | 문서/분 | Chunk·Embedding/초 | Queue P95 | 처리 P95 | 전체 P95 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 16 | 1 | 49.379초 | 19.442 | 2.592 | 42.914초 | 7.121초 | 49.134초 |
| 16 | 2 | 51.898초 | 18.498 | 2.466 | 44.887초 | 6.768초 | 51.742초 |
| 16 | 3 | 50.255초 | 19.102 | 2.547 | 43.794초 | 6.468초 | 50.127초 |
| 32 | 1 | 102.558초 | 18.721 | 2.496 | 92.526초 | 6.974초 | 98.769초 |
| 32 | 2 | 104.335초 | 18.402 | 2.454 | 93.554초 | 7.285초 | 100.665초 |
| 32 | 3 | 105.928초 | 18.125 | 2.417 | 95.968초 | 7.431초 | 102.253초 |

같은 Profile 세 번의 분당 처리량 범위는 16문서에서 18.498~19.442, 32문서에서
18.125~18.721이었다. 한 번의 최고값이 아니라 Profile별 중앙값을 비교 기준으로 사용했다.

## 6. 데이터 완전성 검증

| 문서 수 | 회차 | Chunk 수 | Embedding 수 | 결과 |
|---:|---:|---:|---:|---|
| 16 | 1~3 | 매회 128 | 매회 128 | PASS |
| 32 | 1~3 | 매회 256 | 매회 256 | PASS |

각 Profile 완료 시 다음 불변식을 함께 확인했다.

- 모든 Embedding Job이 `INDEXED`다.
- 각 Job에 성공한 Attempt가 정확히 하나 존재한다.
- 모든 Document와 Version이 `INDEXED`이고 `current_version_id`가 측정 Version을 가리킨다.
- Chunk 수와 Embedding 수가 일치하고 중복 Chunk Embedding이 없다.
- 저장된 모든 Vector의 차원은 1024이며 NaN·Infinity가 없다.
- 자동 Worker 실행 슬롯이 0으로 반환되고 Worker가 살아 있다.

따라서 이 결과는 HTTP 접수 시간만 측정한 값이 아니라 Vector 저장과 검색 Version 전환이 완료된 시점까지의
전체 처리량이다.

## 7. 실행 중 발견하고 해결한 환경 문제

### 7.1 로컬 PostgreSQL SSL 설정

첫 Smoke 시도는 외부 Shell의 SSL 설정이 비-SSL 로컬 PostgreSQL에 적용돼 Flyway 연결 전에 실패했다.
`DB_SSLMODE=disable`을 명시한 뒤 Migration과 전체 Pipeline이 정상 실행됐다.

### 7.2 CPU BGE-M3 응답 제한

기존 Embedding HTTP 응답 제한은 5초로 고정돼 있었다. 로컬 CPU BGE-M3는 정상적인 Batch 응답에도
5초를 넘겨 Worker가 `EMBEDDING_PROVIDER_UNAVAILABLE`로 재시도했다. 연결·응답 제한을 환경 설정으로
분리하고 Benchmark에만 2분 응답 제한을 적용했다. 일반 실행의 기본값은 기존과 같은 5초다.

### 7.3 전체 회귀의 JWT 환경 값

전체 회귀 첫 시도는 `JWT_SECRET` 미설정으로 Spring Context 12건이 연쇄 실패했다. Test 전용 JWT와
`DB_SSLMODE=disable`을 명시한 재실행에서 720개 Test가 모두 통과했다. 첫 실패는 Source 결함이나
Benchmark 실패가 아니며 통과 결과로 계산하지 않았다.

## 8. 검증 결과

| 검증 | 결과 |
|---|---|
| 통계 계약 단위 테스트 | PASS |
| 전용 Gradle Task 노출 | PASS |
| 4문서 실제 BGE-M3 Smoke | PASS, 23초 |
| 16·32문서 각 3회 본 측정 | PASS, 8분 1초 |
| 전체 Java 회귀 | PASS, 720 tests, failure/error/skipped 0 |
| `git diff --check` | PASS |

## 9. 결론과 남은 한계

- 구현됨: 자동 Worker의 실제 문서 인덱싱 처리량과 Queue·처리·전체 지연 분포를 반복 측정할 수 있다.
- 검증됨: 16문서에서 32문서로 Queue가 두 배가 되어도 분당 처리량 감소는 약 3.7%였다.
- 검증됨: 여섯 실행 모두 Chunk·Embedding·1024차원 Vector 완전성을 만족했다.
- 관찰됨: 동시 실행 슬롯 2개에서는 Queue 증가가 처리 시간보다 전체 P95를 지배했다.
- 한계: 단일 Local Apple Silicon CPU 장비의 결과로, 공식 OpenSQL Server나 GPU BGE-M3 결과가 아니다.
- 한계: TXT 6,400자 고정 입력이라 PDF·DOCX Parser 비용이나 다양한 문서 길이 분포를 대표하지 않는다.
- 한계: Worker 수·동시 실행 슬롯·Embedding Batch Size를 바꾼 수평 확장 비교는 이번 범위에 포함하지 않았다.
- 후속: 같은 Harness로 Worker 수·동시성 변화, Queue 적체와 Backpressure, 장애 주입 Profile을 비교해야 한다.
