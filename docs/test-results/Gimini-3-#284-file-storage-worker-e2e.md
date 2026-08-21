# 파일 저장소 Adapter와 Worker 전체 E2E 결과

Closes #284

## 목적

Local Filesystem, MinIO, AWS S3 Adapter가 같은 파일 저장소 Port 계약으로 실제 HTTP 업로드와 자동
인덱싱 Worker 전체 흐름을 완료하는지 검증한다. 기능 구현 문서와 분리해 재현 명령, 정상·오류 시나리오,
실행 결과만 기록한다.

## 실행 환경

- 실행 일시: 2026-08-21 (Asia/Seoul)
- Java: 17
- PostgreSQL: `pgvector/pgvector:0.8.1-pg17`, Host Port `55433`
- MinIO API: `http://127.0.0.1:9000`
- BGE-M3: `http://127.0.0.1:8000`
- S3 검증 Endpoint: 로컬 MinIO S3-compatible API, Path-style 사용

실제 AWS S3 계정 검증이 아니라 AWS SDK S3 Client가 S3-compatible Endpoint에서 수행하는 실제
Put/Get/Delete 계약 검증이다. IAM Policy, VPC, 실제 AWS Region Network는 배포환경에서 별도로 확인한다.

## 격리 방식

| Provider | DB Schema | 파일 격리 |
|---|---|---|
| Local Filesystem | `docgrid_local_storage_worker_e2e` | 실행별 `build/storage-worker-e2e/local-{uuid}` |
| MinIO | `docgrid_minio_storage_worker_e2e` | 실행별 `docgrid-minio-worker-{uuid}` Bucket |
| S3 Adapter | `docgrid_s3_storage_worker_e2e` | 실행별 `docgrid-s3-worker-{uuid}` Bucket |

각 Test는 Worker Thread를 먼저 종료한 뒤 자신이 만든 Bucket 또는 Local Root와 DB Schema만 제거한다.
기존 개발 Schema, Bucket, Object, Docker Volume은 삭제하지 않는다.

## 시나리오

### 정상 시나리오

Provider마다 다음 단계를 같은 Test로 실행했다.

1. Test Schema에 Flyway와 Seed를 적용하고 자동 Worker를 등록한다.
2. 실제 ADMIN 로그인과 Multipart HTTP로 PDF 문서를 업로드한다.
3. DB의 `storage_provider`, `bucket_name`, `object_key`를 읽는다.
4. 해당 Snapshot으로 실제 저장된 원본 Byte가 업로드 Byte와 같은지 확인한다.
5. Worker가 실제 Parser와 BGE-M3를 호출해 Job·Document·Version을 `INDEXED`로 완료할 때까지 기다린다.
6. Chunk 수가 양수이고 Embedding 수와 같으며 Attempt가 `SUCCESS`인지 확인한다.

### 오류 시나리오

정상 인덱싱 뒤 실제 Object를 삭제하고 같은 `StoredFile` Snapshot으로 다시 읽었다. 세 Adapter 모두
`FILE_OBJECT_NOT_FOUND`(`DOCUMENT-STORAGE-002`)를 반환해 Object 누락을 저장소 비가용과 구분했다.

추가로 실제 MinIO의 다른 Bucket에 Object를 만든 뒤 현재 `STORAGE_BUCKET`과 다른 Snapshot으로 읽었다.
원격 Object 존재 여부와 무관하게 `FILE_STORAGE_CONFIGURATION_MISMATCH`
(`DOCUMENT-STORAGE-003`)로 I/O 전에 거부되는 것을 확인했다.

## 실행 명령과 결과

### Provider·Worker 전체 E2E

```bash
DB_HOST=127.0.0.1 DB_PORT=55433 \
  JWT_SECRET=docgrid-test-secret-key-with-at-least-thirty-two-bytes \
  ./backend/gradlew -p backend storageWorkerE2eTest
```

결과: `BUILD SUCCESSFUL`, 3개 Test 모두 통과.

| Test | 결과 | Test 실행 시간 |
|---|---|---:|
| Local Filesystem 저장소와 Worker 전체 E2E | PASS | 5.365초 |
| MinIO 저장소와 Worker 전체 E2E | PASS | 0.762초 |
| S3 저장소와 Worker 전체 E2E | PASS | 0.851초 |

Gradle 전체 실행 시간은 14초였다. 위 시간은 모델 Warm-up과 Host 상태에 따라 달라질 수 있으며 성능
합격 기준으로 사용하지 않는다.

### 실제 MinIO 통합 테스트

```bash
DB_HOST=127.0.0.1 DB_PORT=55433 \
  JWT_SECRET=docgrid-test-secret-key-with-at-least-thirty-two-bytes \
  MINIO_ENDPOINT=http://127.0.0.1:9000 \
  MINIO_ACCESS_KEY=minioadmin \
  MINIO_SECRET_KEY=minioadmin1234 \
  ./backend/gradlew -p backend minioIntegrationTest
```

결과: `BUILD SUCCESSFUL`, 5개 Test 모두 통과.

### 일반 백엔드 회귀 테스트

```bash
DB_HOST=127.0.0.1 DB_PORT=55433 \
  JWT_SECRET=docgrid-test-secret-key-with-at-least-thirty-two-bytes \
  ./backend/gradlew -p backend test
```

1,073개 중 저장소 변경과 무관한 기존 `RagJobWorkerConcurrentQueueIntegrationTest` 한 건이 전체 Suite의
외부 Ollama 90초 응답 경쟁에서 시간 초과됐다. 같은 Commit에서 해당 Test만 단독 재실행한 결과는
`BUILD SUCCESSFUL`이었다.

```bash
DB_HOST=127.0.0.1 DB_PORT=55433 \
  JWT_SECRET=docgrid-test-secret-key-with-at-least-thirty-two-bytes \
  ./backend/gradlew -p backend test \
  --tests com.opensource.docgrid.domain.rag.integration.RagJobWorkerConcurrentQueueIntegrationTest
```

따라서 저장소·Worker E2E 세 건과 영향 범위 테스트는 모두 통과했으며, 전체 Suite에는 기존 RAG 동시성
Flaky 한 건이 별도 개선 대상으로 남아 있다.

### 빌드

```bash
./backend/gradlew -p backend build -x test
```

결과: `BUILD SUCCESSFUL`.
