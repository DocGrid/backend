# #101 BGE-M3 배치 문서 임베딩 연동 지원 추가 구현

## 1. 배경

현재 Embedding Server는 `POST /embed`로 Text 한 건만 처리하고, Java
`DocumentEmbeddingGenerator`는 문서 Chunk를 하나씩 순회하며 같은 API를 반복 호출한다.
Chunk별 호출은 결과를 모두 생성한 뒤 기존 완료 Transaction에서 원자 저장하므로 데이터 정합성은
보장하지만, 문서가 커질수록 HTTP 왕복과 BGE-M3 `encode` 호출 횟수가 Chunk 수에 비례한다.

이번 작업은 기존 단건 Query Embedding 계약을 유지하면서 문서 Chunk 전용 Batch 계약을 추가한다.
외부 모델 호출은 계속 DB Transaction 밖에서 수행하고, 모든 Batch가 성공한 뒤에만 기존 완료
Transaction으로 전체 Vector Set을 전달한다.

## 2. 목표

1. BGE-M3가 여러 Chunk Text를 한 번의 `encode` 호출로 처리하게 한다.
2. Java가 설정 가능한 Batch 크기로 Chunk를 나누어 외부 서버를 호출하게 한다.
3. 응답 모델, 개수, 순서, Vector 차원과 유한 값을 저장 전에 검증한다.
4. 여러 Batch 중 하나라도 실패하면 Embedding을 한 건도 부분 저장하지 않는다.
5. 기존 `/embed`와 `QueryEmbeddingService` 계약을 변경하지 않는다.
6. 전송 장애를 기존 Worker 자동 Retry 정책에 연결한다.

## 3. 기존 계약과 변경 경계

| 영역 | 유지 | 변경 |
| --- | --- | --- |
| Query Embedding | `POST /embed`, `EmbeddingClient.embed(String)` | 없음 |
| 문서 Embedding | 준비·외부 호출·완료 Transaction 분리 | 외부 호출을 Batch로 전환 |
| Vector | `float[]`, 1024차원, 유한 값, SHA-256 Hash | 없음 |
| 저장 | 전체 Chunk Embedding의 단일 완료 Transaction | 없음 |
| Worker | Provider 장애는 Retry 가능 | Batch 전송 장애도 같은 분류 사용 |
| Chunking | 고정 문자 수와 Overlap | 없음 |

PDF·DOCX Parsing, Query Vector, 검색, RAG, MCP와 공식 OpenSQL 원격 검증은 이 작업 범위가 아니다.

## 4. Python Batch API 계약

### 4.1 요청

`POST /embed/batch`

```json
{
  "texts": ["첫 번째 Chunk", "두 번째 Chunk"],
  "batch_size": 16
}
```

- `texts`: 1개 이상 64개 이하의 순서가 있는 문자열 목록
- 각 Text: 공백만 있는 값은 허용하지 않음
- `batch_size`: 1 이상 64 이하
- Text 내용은 검증을 위해 `trim`하지 않고 원문 그대로 모델에 전달

### 4.2 응답

```json
{
  "model": "BAAI/bge-m3",
  "embeddings": [
    {"index": 0, "vector": [0.1, 0.2]},
    {"index": 1, "vector": [0.3, 0.4]}
  ]
}
```

- `model`: 서버가 실제로 사용하는 고정 모델 식별자
- `index`: 요청 `texts`의 0 기반 위치
- `vector`: 해당 Text의 Dense Vector
- 응답은 요청 순서를 유지하고 `index`를 함께 반환해 Java가 순서를 검증할 수 있게 함

### 4.3 실행 규칙

1. Lifespan Thread에서 기존과 같이 BGE-M3를 한 번만 적재한다.
2. 단건과 Batch API가 같은 내부 Encoding 함수를 사용한다.
3. Batch API는 입력 전체를 `model.encode(texts, batch_size=..., max_length=8192)`에 전달한다.
4. `dense_vecs`가 없거나 입력 개수와 다르면 서버 내부 오류로 응답한다.
5. 기존 단건 응답 `{ "vector": [...] }` 형식은 유지한다.

## 5. Java 전송 계약

새 요청·응답 Record를 추가한다.

- `EmbedBatchRequest`: Text 불변 목록과 서버 내부 Batch 크기
- `EmbedBatchServerResponse`: 모델 식별자와 순서가 있는 Embedding 결과
- `EmbedBatchItemResponse`: 요청 위치와 Vector

`EmbeddingClient.embedBatch(List<String>, int)`는 다음 경계만 책임진다.

1. `POST /embed/batch` 호출
2. HTTP·연결·Read Timeout을 `EMBEDDING_SERVER_UNAVAILABLE`로 변환
3. Null 응답, 빈 모델, Null 결과 목록, 요청 개수 불일치, 잘못된 Index를
   `DOCUMENT_EMBEDDINGS_INCONSISTENT`로 변환
4. 응답 Vector 자체의 차원과 유한 값 검증은 Job 고정 Model을 아는 Generator에 위임

단건 `embed(String)`은 변경하지 않는다.

## 6. Batch 크기 설정

`embedding.document.batch-size` 설정을 추가한다.

```yaml
embedding:
  document:
    batch-size: ${EMBEDDING_DOCUMENT_BATCH_SIZE:16}
```

- 기본값: 16
- 허용 범위: 1~64
- Spring Boot 기동 시 `@Validated` Configuration Properties로 범위를 검증
- 운영 환경은 환경변수로 조정하며 코드와 Docker Image를 변경하지 않음

## 7. 문서 Embedding 생성 흐름

`DocumentEmbeddingGenerator`는 다음 순서로 동작한다.

1. `EmbeddingWork`의 Version, Model, 차원과 Chunk 목록을 검증한다.
2. Chunk 목록을 설정된 Batch 크기로 순서대로 자른다.
3. 각 Batch의 Text 목록을 `EmbeddingClient.embedBatch`에 전달한다.
4. 응답 모델이 Job에 고정된 `EmbeddingModel.modelName`과 같은지 확인한다.
5. 각 응답 Vector를 원래 Chunk 위치에 결합한다.
6. 차원·NaN·Infinity를 검증하고 Vector Hash를 계산해 Draft를 만든다.
7. 모든 Batch가 성공한 경우에만 전체 Draft 목록을 호출자에게 반환한다.

`EmbeddingWork`에는 외부 응답 모델을 대조할 수 있도록 기존 Model ID·차원에 Model Name을 추가한다.

## 8. 원자성과 실패 정책

- 준비 Transaction은 외부 호출 전에 Version을 `EMBEDDING`으로 전환할 수 있지만 Embedding 행은
  저장하지 않는다.
- Batch 호출과 검증은 Transaction 밖에서 실행한다.
- 중간 Batch에서 전송 또는 검증 오류가 발생하면 완료 Transaction을 호출하지 않는다.
- 따라서 Embedding 행은 0건으로 유지되고 Worker가 기존 실패 종료 Service를 실행한다.
- 연결, Timeout, HTTP 오류는 `EMBEDDING_PROVIDER_UNAVAILABLE`로 분류돼 자동 Retry 대상이다.
- 모델, 개수, 순서, 차원, NaN·Infinity 오류는 잘못된 Provider 결과 또는 상태 불일치로 분류하며
  자동 Retry로 무한 반복하지 않는다.

## 9. 테스트 설계

### 9.1 Python 계약 테스트

- 단건 API 응답 회귀
- Batch 입력 순서와 Index 보존
- `encode`가 입력 전체로 한 번 호출되는지 확인
- 빈 목록, 공백 Text, Batch 크기 범위 오류
- 모델 미적재 503
- `dense_vecs` 누락과 응답 개수 불일치

실제 대형 모델을 Unit Test마다 내려받지 않도록 가짜 Model을 주입한다.

### 9.2 Java 단위 테스트

- Client Batch 정상 응답
- Null 응답, 모델 누락, 결과 개수·Index 오류
- RestClient 장애 변환
- Chunk가 Batch보다 작음, 같음, 큼
- 여러 Batch의 호출 순서와 Draft 순서
- 모델 불일치, 차원 불일치, NaN·Infinity
- 두 번째 Batch 실패 시 뒤 Batch를 호출하지 않음

### 9.3 PostgreSQL 통합 테스트

- Batch 성공 후 모든 Chunk가 `vector(1024)`로 저장됨
- 중간 Batch 실패 후 Embedding 0건
- 동일 요청 재실행의 멱등 재생
- Worker Provider 장애 Retry 회귀

### 9.4 실제 BGE-M3 Smoke

- Embedding Server Container Health 확인
- 세 문장을 한 Batch로 호출해 1024차원과 유한 값을 확인
- 의미가 가까운 문장 쌍의 Cosine Similarity가 무관한 문장보다 높은지 확인
- 실제 모델 실행이 불가능하면 원인을 기록하고 Unit·통합 검증과 구분

## 10. 보안과 로그

- 문서 원문, Vector 전체와 외부 응답 Body를 Application Log에 기록하지 않는다.
- 오류 Log에는 예외 Type만 남기고 Endpoint·문서 내용·원격 연결정보를 포함하지 않는다.
- 모델과 Batch 크기는 비밀정보가 아니지만 실제 서버 인증정보가 추가될 경우 환경변수로만 주입한다.

## 11. 변경 대상

| 영역 | 파일 |
| --- | --- |
| 설계 | `docs/design/Gimini-3-#101-bge-m3-batch-document-embedding.md` |
| Python API | `embedding-server/main.py` |
| Python Test | `embedding-server/test_main.py` 및 테스트 의존성 |
| Java 설정 | Embedding Batch Configuration Properties, `application.yml` |
| Java DTO | Batch 요청·응답 Record |
| Java Client | `EmbeddingClient` |
| Java 생성 흐름 | `DocumentEmbeddingGenerator`, `EmbeddingWork` |
| Java Test | Client·Generator·Transaction·Integration Test |
| 실행 결과 | `docs/test-results/Gimini-3-#101-bge-m3-batch-document-embedding.md` |

## 12. 커밋 분리

1. `docs: #101 BGE-M3 배치 문서 임베딩 설계 추가`
2. `feat: #101 BGE-M3 배치 Embedding API 구현`
3. `feat: #101 문서 Embedding 배치 호출 연동`
4. `test: #101 배치 Embedding 원자성 및 회귀 검증`
5. `docs: #101 BGE-M3 배치 Embedding 검증 결과 기록`

각 구현 Commit은 독립적으로 Compile 또는 해당 영역 Test가 통과해야 한다.

## 13. 완료 조건

- 기존 `/embed` 단건 계약이 유지된다.
- `/embed/batch`가 입력 순서와 같은 개수의 결과를 반환한다.
- Java가 1~64 범위의 설정값에 따라 Chunk를 Batch 호출한다.
- 모델, 응답 개수·순서, 차원과 모든 Vector 값이 저장 전에 검증된다.
- 중간 Batch 실패 시 Embedding 행이 부분 저장되지 않는다.
- 성공한 전체 Vector Set은 기존 하나의 완료 Transaction에서 저장된다.
- Provider 전송 장애가 Worker 자동 Retry 흐름으로 연결된다.
- PostgreSQL 17 + pgvector 0.8.1 회귀 검증이 통과한다.
- 실제 BGE-M3 Smoke 결과 또는 실행 불가 사유가 Test Result에 기록된다.

Closes #101
