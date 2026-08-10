# Issue #143 PDF·DOCX 전체 인덱싱 E2E 부하 Benchmark 결과

## 1. 결과 요약

PostgreSQL 17.8, pgvector 0.8.1, MinIO와 실제 `BAAI/bge-m3`를 연결하고 Text Layer PDF와
OOXML DOCX의 업로드부터 `INDEXED` 전환까지 전체 경로를 측정했다. PDF·DOCX 2개씩 4문서를 예열한
뒤 50문서와 100문서 Profile을 각각 2회 실행했다.

| 문서 수 | 구성 | 반복 | 중앙 총 시간 | 문서/분 | Chunk·Embedding/초 | 전체 P95 |
|---:|---|---:|---:|---:|---:|---:|
| 50 | PDF 25 + DOCX 25 | 2회 | 96.633초 | 31.046 | 2.070 | 92.178초 |
| 100 | PDF 50 + DOCX 50 | 2회 | 187.138초 | 32.063 | 2.138 | 178.533초 |

본 측정 300문서에서 1,200개 Chunk와 1,200개 1024차원 Embedding이 저장됐다. 업로드 실패,
실패·재시도 Attempt, 미완료 Job과 중복 Vector는 모두 0건이었다.

문서 수를 50개에서 100개로 두 배 늘려도 분당 처리량은 약 3.3% 증가한 범위에서 유지됐다. 처리
P95는 4.093초에서 4.026초로 비슷했고, 전체 P95 증가는 Queue 대기 P95가 88.355초에서
174.684초로 늘어난 영향이다.

## 2. 공개 가능한 실행 환경

| 항목 | 값 |
|---|---|
| Database | PostgreSQL 17.8, Local Docker |
| pgvector | 0.8.1 |
| Object Storage | MinIO, Local Docker |
| Embedding Provider | `BAAI/bge-m3`, Local Docker CPU 추론 |
| Vector 차원 | 1024 |
| Embedding Batch Size | 32 |
| Worker 실행 슬롯 | 2 |
| Worker Polling 주기 | 50 ms |
| 업로더 Thread | 8 |
| PDF | 2페이지 Text Layer, 페이지당 1,600자 |
| DOCX | Heading·본문 2개 Section, Section당 1,600자 |
| 문서당 Chunk·Embedding | 각각 4개 |
| Warm-up | PDF 2 + DOCX 2 |
| 본 측정 | 50 / 100문서, Profile당 2회 |
| Application | Spring Boot 3.5.16, Java 17 |
| 실행 장비 | macOS `aarch64`, 가용 Processor 10개 |
| 실행 일자 | 2026-08-11 KST |

DB·MinIO·JWT Credential은 결과에 기록하지 않았다. 이 수치는 단일 Apple Silicon 로컬 장비의
개발 기준선이며, 공식 OpenSQL 원격 Server 성능이나 운영 SLO가 아니다.

## 3. 측정 경로

각 문서는 다음 실제 경로를 통과했다.

```text
PDF·DOCX Binary 생성
→ 인증 Multipart HTTP 업로드
→ MinIO 원본 저장
→ Embedding Job PENDING
→ 자동 Worker Polling·Claim·Attempt
→ PDF·DOCX Parsing
→ Chunk·페이지·Section Metadata 저장
→ 실제 BGE-M3 Batch 호출
→ pgvector vector(1024) 저장
→ Version·Document·Job INDEXED
→ current_version 전환
→ Worker 실행 슬롯 반환
```

Profile마다 전용 Schema의 Job·Document·Chunk·Embedding과 전용 MinIO Bucket의 Object를 초기화해
이전 실행이 다음 수치에 포함되지 않게 했다. PDF와 DOCX는 업로드 순서에서 교대로 배치했다.

## 4. 실행 방법

PostgreSQL, MinIO와 Embedding Server가 모두 건강한 로컬 환경에서 실행했다.

```bash
docker compose up -d postgres minio embedding-server
DB_SSLMODE=disable ./gradlew documentIndexingE2ELoadTest
```

구조화 원시 결과는 Git에 포함하지 않는 다음 경로에 생성된다.

```text
build/reports/document-indexing-e2e-load/document-indexing-e2e-load.json
```

작은 실제 환경 Smoke는 다음 설정으로 실행했다.

```bash
DB_SSLMODE=disable ./gradlew documentIndexingE2ELoadTest \
  -Ddocument.indexing.e2e.load.warm-up-documents=2 \
  -Ddocument.indexing.e2e.load.document-counts=4 \
  -Ddocument.indexing.e2e.load.repetitions=1 \
  -Ddocument.indexing.e2e.load.output=build/reports/document-indexing-e2e-load/smoke.json
```

Smoke는 PDF 2 + DOCX 2, Chunk·Embedding 각 16개를 7.192초에 처리했고 33.370문서/분을
기록했다. 전체 Gradle 실행은 19초였다.

## 5. 반복별 결과

| 문서 수 | 회차 | 총 시간 | 문서/분 | Chunk·Embedding/초 | Upload P95 | Queue P95 | 처리 P95 | 전체 P95 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 1 | 96.045초 | 31.235 | 2.082 | 165.7ms | 87.903초 | 3.961초 | 91.590초 |
| 50 | 2 | 97.222초 | 30.857 | 2.057 | 84.9ms | 88.808초 | 4.226초 | 92.767초 |
| 100 | 1 | 188.285초 | 31.867 | 2.124 | 106.3ms | 175.755초 | 4.189초 | 179.814초 |
| 100 | 2 | 185.991초 | 32.260 | 2.151 | 71.4ms | 173.612초 | 3.863초 | 177.252초 |

50문서 처리량 범위는 30.857~31.235문서/분, 100문서는 31.867~32.260문서/분이었다. 단일
최고값 대신 두 반복의 중앙값을 비교 기준으로 사용했다.

## 6. 형식별 결과

각 Profile 전체 시간을 분모로 사용해 형식별 문서·Chunk 처리 기여도를 계산했다.

| 문서 수 | 형식 | 문서/분 중앙값 | Chunk/초 중앙값 | Upload P95 | Queue P95 | 처리 P95 | 전체 P95 |
|---:|---|---:|---:|---:|---:|---:|---:|
| 50 | PDF | 15.523 | 1.035 | 124.1ms | 85.947초 | 4.098초 | 89.800초 |
| 50 | DOCX | 15.523 | 1.035 | 121.6ms | 87.994초 | 3.981초 | 91.820초 |
| 100 | PDF | 16.032 | 1.069 | 88.1ms | 173.244초 | 4.065초 | 177.075초 |
| 100 | DOCX | 16.032 | 1.069 | 88.3ms | 173.388초 | 4.021초 | 177.139초 |

동일 길이의 결정적 Fixture에서는 PDF와 DOCX 처리 P95 차이가 50문서에서 약 0.12초,
100문서에서 약 0.04초였다. 이 결과는 Parser 비용이 현재 전체 처리량 병목이 아니며 Queue 대기가
전체 꼬리 지연을 지배함을 보여준다.

## 7. 데이터 완전성

| 문서 수 | 회차 | PDF·DOCX | Chunk | Embedding | 결과 |
|---:|---:|---:|---:|---:|---|
| 50 | 1 | 25 + 25 | 200 | 200 | PASS |
| 50 | 2 | 25 + 25 | 200 | 200 | PASS |
| 100 | 1 | 50 + 50 | 400 | 400 | PASS |
| 100 | 2 | 50 + 50 | 400 | 400 | PASS |

각 Profile 완료 시 다음 불변식을 함께 검증했다.

- 모든 HTTP 업로드가 성공하고 MinIO Object 수가 문서 수와 일치한다.
- MinIO Object와 DB FileObject의 크기·Content-Type이 원본과 일치한다.
- 모든 Job·Version·Document가 `INDEXED`이고 `current_version_id`가 측정 Version을 가리킨다.
- Job별 성공 Attempt가 정확히 하나이며 Retry Count는 0이다.
- `LOCKED → PARSE_STARTED → CHUNKED → EMBEDDING_STARTED → INDEXED` 순서를 유지한다.
- Chunk와 Embedding이 일대일이고 중복 Chunk Embedding이 없다.
- 모든 활성 Vector의 차원이 1024다.
- 모든 PDF Chunk가 페이지 번호를 가지며 문서마다 페이지 1·2가 보존된다.
- 모든 DOCX Chunk가 Section 제목을 가지며 문서마다 두 Section이 보존된다.
- Profile 종료 때 `PENDING`·`PROCESSING` Job과 점유된 Worker 슬롯이 없다.

## 8. 검증 결과

| 검증 | 결과 |
|---|---|
| 형식별 통계 계약 단위 테스트 | PASS |
| 전용 Gradle Task 노출 | PASS |
| PDF 2 + DOCX 2 실제 Smoke | PASS, Gradle 19초 |
| 50·100문서 각 2회 본 측정 | PASS, Gradle 9분 44초 |
| 본 측정 300문서·1,200 Vector 완전성 | PASS |
| 기존 실제 PDF·DOCX 로컬 E2E | PASS, 2 tests |
| 전체 일반 Java 회귀 | PASS, 746 tests |
| `git diff --check` | PASS |

## 9. 결론과 한계

- 구현됨: 실제 PDF·DOCX 혼합 전체 Pipeline을 50·100문서 규모로 반복 측정할 수 있다.
- 검증됨: 300문서가 실패·재시도 없이 모두 `INDEXED`로 수렴했다.
- 검증됨: 문서 수를 두 배로 늘려도 분당 처리량은 약 31~32문서로 유지됐다.
- 관찰됨: PDF와 DOCX의 처리 P95는 비슷하고 Queue 대기가 전체 P95 증가를 지배했다.
- 검증됨: 페이지·Section Metadata, Chunk·Embedding 일대일과 1024차원 Vector가 모두 유지됐다.
- 한계: Text Layer PDF만 포함하며 스캔 PDF와 OCR은 범위 밖이다.
- 한계: 동일 길이의 결정적 문서라 실제 사용자 파일의 크기·표·이미지 분포를 대표하지 않는다.
- 한계: 단일 Worker Node, 실행 슬롯 2개와 Local CPU BGE-M3 결과다.
- 후속: 실제 사용자 Corpus, 장애 주입과 공식 OpenSQL 원격 환경에서 같은 Harness를 재검증할 수 있다.
