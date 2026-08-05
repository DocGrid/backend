# #101 BGE-M3 배치 문서 임베딩 연동 검증 결과

## 1. 검증 개요

- 실행 일자: 2026-08-05
- Branch: `feature/101`
- 검증 기준 Commit: `770711fffb028006debe61044b2dbcd08b1ca0a3`
- Embedding Model: `BAAI/bge-m3`
- Vector Dimension: 1024
- Local Database: PostgreSQL 17 + pgvector 0.8.1
- 문서 Batch 기본값: 16
- 문서 Batch 허용 범위: 1~64

이번 검증은 기존 Query Embedding의 단건 `/embed` 계약을 유지하면서 문서 Chunk 경로에 추가한
`/embed/batch` 계약, Java Batch 분할, 응답 검증과 전체 Vector Set 원자 저장 경계를 대상으로 한다.

## 2. 구현 계약 검증

### 2.1 Python Batch API

`POST /embed/batch`는 다음 계약으로 구현했다.

```json
{
  "texts": ["첫 번째 Chunk", "두 번째 Chunk"],
  "batch_size": 16
}
```

- Text 목록은 1~64개만 허용한다.
- 공백 Text를 거부하고 원문은 Trim 없이 모델에 전달한다.
- 입력 전체를 BGE-M3 `encode`에 한 번 전달한다.
- 응답에 고정 모델 식별자와 요청 위치별 Index를 포함한다.
- Dense Vector 누락이나 입력과 다른 결과 개수는 내부 오류로 거부한다.
- 기존 단건 `/embed` 응답 형식은 변경하지 않았다.

### 2.2 Java Batch 경계

- `embedding.document.batch-size`를 `@Validated` 설정으로 추가했다.
- `EmbeddingClient.embedBatch`가 HTTP 장애를 `EMBEDDING_SERVER_UNAVAILABLE`로 변환한다.
- Null 응답, 빈 모델, 개수·Index 불일치는 저장 전에 거부한다.
- `EmbeddingWork`가 Model Name을 고정하고 완료 Transaction에서 다시 대조한다.
- `DocumentEmbeddingGenerator`가 Chunk를 설정 크기로 나누고 Draft 순서를 보존한다.
- 모델, 차원, NaN과 Infinity를 전체 저장 전에 검증한다.

## 3. Python 계약 테스트

기존 Embedding Server Image에 현재 `main.py`를 읽기 전용으로 Mount하고, 일회성 Container에
Test 의존성만 설치했다. 가짜 Model을 주입했으므로 이 단계에서는 모델 다운로드나 GPU 실행을 하지
않았다.

```bash
python3 -m pytest test_main.py
```

결과:

```text
9 passed in 2.77s
```

검증 항목:

- 단건 응답 회귀
- Batch 입력 순서와 Index 보존
- 입력 전체의 단일 `encode` 호출
- 빈 목록, 공백 Text와 Batch 크기 범위 오류
- 모델 미적재 503
- Dense Vector 누락과 응답 개수 불일치

## 4. Java 단위 테스트

직접 영향 범위와 Worker 실패 분류 회귀를 실행했다.

```bash
./gradlew test \
  --tests 'com.opensource.docgrid.domain.embedding.client.EmbeddingClientTest' \
  --tests 'com.opensource.docgrid.domain.embedding.config.EmbeddingBatchPropertiesTest' \
  --tests 'com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingGeneratorTest' \
  --tests 'com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingServiceTest' \
  --tests 'com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionServiceTest' \
  --tests 'com.opensource.docgrid.domain.worker.service.WorkerIndexingFailureClassifierTest'
```

결과: 성공

- 기본값 16과 1~64 설정 범위
- Client 정상 응답 및 Null·모델·개수·Index 오류
- Client 연결 오류의 Retry 가능 Provider 장애 변환
- Chunk가 Batch 크기보다 작거나 같거나 큰 경우의 분할
- 여러 Batch 호출 순서와 Draft 순서
- 모델·차원·유한 값 검증
- 두 번째 Batch 실패 이후 호출 중단
- 준비·완료 사이 Model Name 변경 차단

## 5. PostgreSQL 17 원자 저장 통합 테스트

실행 중인 `pgvector/pgvector:0.8.1-pg17` Container를 사용했다.

```bash
DB_SSLMODE=disable \
./gradlew test \
  --tests 'com.opensource.docgrid.domain.embedding.integration.DocumentEmbeddingIntegrationTest'
```

결과: 성공

- Batch 크기 2로 Chunk 3개를 두 번 호출하고 `vector(1024)` 3건을 저장했다.
- 동일 요청 재실행은 외부 호출 없이 기존 전체 결과를 재생했다.
- 두 번째 Batch에서 Provider 장애가 발생하면 Embedding 행은 0건으로 유지됐다.
- 같은 실행의 동시 요청 2개는 생성 1건과 재생 1건으로 수렴했다.
- Version 상태와 `EMBEDDING_STARTED` Event의 단일 기록을 확인했다.

첫 실행은 Host의 SSL 설정이 Local Container에 전달돼 다음 오류로 초기화 단계에서 실패했다.

```text
The server does not support SSL.
```

제품 변경 없이 Test Process에 `DB_SSLMODE=disable`을 명시해 재실행했고 통과했다.

## 6. 전체 회귀 테스트

```bash
JWT_SECRET=<ephemeral-test-value> \
DB_SSLMODE=disable \
./gradlew test
```

결과:

```text
BUILD SUCCESSFUL in 19s
588 tests, 0 failed
```

최초 전체 실행에서는 `JWT_SECRET`을 주입하지 않아 570개 실행 중 11개 Spring Context Test가
초기화 단계에서 실패했다. Repository에 Secret을 추가하지 않고 Test Process에만 임시 값을 주입해
재실행했으며 전체 회귀가 통과했다.

## 7. 실제 BGE-M3 Smoke

기존 Hugging Face Cache Volume을 보존한 일회성 Container에 현재 `main.py`를 읽기 전용으로
Mount했다. Health 성공 후 다음 세 문장을 한 Batch로 전달했다.

1. `강아지가 공원에서 뛰어놀고 있습니다.`
2. `공원에서 개가 신나게 달리고 있습니다.`
3. `데이터베이스 인덱스는 검색 성능을 높입니다.`

결과:

```json
{
  "model": "BAAI/bge-m3",
  "count": 3,
  "dimensions": [1024, 1024, 1024],
  "all_finite": true,
  "related_cosine": 0.849536,
  "unrelated_cosine": 0.350154,
  "semantic_order": true
}
```

- 응답 Model과 Index 순서가 계약과 일치했다.
- 세 Vector가 모두 1024차원이고 NaN·Infinity가 없었다.
- 의미가 가까운 문장 쌍의 Cosine Similarity가 무관한 문장보다 높았다.
- 검증용 Container는 종료 후 자동 제거했고 Model Cache Volume은 보존했다.

이 Smoke 수치는 기능 연결을 확인하기 위한 단일 표본이며 검색 품질 기준선이나 성능 수치로 사용하지
않는다.

## 8. 원자성과 실패 정책 판정

| 시나리오 | 결과 | 저장 상태 |
| --- | --- | --- |
| 모든 Batch 성공 | 통과 | 전체 Chunk Vector를 한 Transaction에서 저장 |
| 두 번째 Batch 전송 실패 | 통과 | Embedding 0건 |
| 모델 식별자 불일치 | 통과 | 완료 Transaction 미호출 |
| 응답 개수·Index 불일치 | 통과 | 완료 Transaction 미호출 |
| 차원·NaN·Infinity 오류 | 통과 | 완료 Transaction 미호출 |
| Provider 연결 장애 | 통과 | Worker 자동 Retry 가능 유형으로 분류 |
| 동일 실행 재호출 | 통과 | 기존 전체 결과 재생 |

## 9. 미실행 및 후속 범위

- PDF·DOCX Parsing은 별도 작업에서 구현한다.
- 업로드부터 MinIO, 자동 Worker, 실제 BGE-M3와 Vector 저장까지의 전체 관통 E2E는 별도 작업에서
  검증한다.
- Rocky Linux 9.7 x86-64 공식 OpenSQL 17.8 환경 검증은 아직 실행하지 않았다.
- Batch 크기별 처리량과 메모리 사용량 Benchmark는 이번 기능 정합성 검증 범위가 아니다.

## 10. 최종 판정

| 완료 조건 | 결과 |
| --- | --- |
| 기존 `/embed` 단건 계약 유지 | 통과 |
| `/embed/batch` 순서·개수 계약 | 통과 |
| 설정 가능한 1~64 Batch 분할 | 통과 |
| 모델·개수·순서·차원·유한 값 사전 검증 | 통과 |
| 중간 Batch 실패 시 부분 저장 방지 | 통과 |
| 전체 Vector Set 원자 저장 | 통과 |
| Provider 장애 자동 Retry 분류 | 통과 |
| PostgreSQL 17 + pgvector 회귀 | 통과 |
| 실제 BGE-M3 1024차원 Smoke | 통과 |

이번 작업의 구현과 자동·통합·실제 모델 검증 범위는 모두 통과했다.
