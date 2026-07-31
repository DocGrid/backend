# Chunk Embedding 생성 및 Vector 저장 설계

## 1. 목표

현재 Worker가 소유한 `PROCESSING` Embedding Job의 `CHUNKED` Document Version을 대상으로,
Job에 고정된 Embedding Model을 사용해 모든 Chunk의 Vector를 생성하고 `embeddings`에 원자적으로
저장한다.

핵심 결과는 다음과 같다.

- Job ID, Attempt ID, Worker ID, Claim Token을 하나의 실행 Context로 유지한다.
- 현재 활성 모델을 다시 선택하지 않고 `EmbeddingJob.embeddingModel`을 사용한다.
- `chunk_index` 오름차순으로 Chunk를 한 건씩 외부 Embedding Server에 전달한다.
- 외부 HTTP 호출 동안 DB Transaction과 행 잠금을 유지하지 않는다.
- Vector 전체가 검증된 경우에만 Version·Model 단위 Embedding Set을 한 Transaction으로 저장한다.
- 처리 전후에 현재 Claim, Attempt와 Lease를 다시 검증해 오래된 Worker의 저장을 차단한다.
- 최초 실행에서 Version을 `CHUNKED`에서 `EMBEDDING`으로 전환하고 시작 이벤트를 한 번 기록한다.
- 저장 완료 뒤에도 Version은 `EMBEDDING`을 유지한다.
- 순차·동시 재호출은 중복 Row를 만들지 않고 기존 전체 결과로 수렴한다.

## 2. 비범위

- Embedding Job의 `INDEXED`, `FAILED` 전환
- Document Version의 `INDEXED`, `FAILED` 전환
- Document의 `current_version_id`, 상태 변경
- Embedding Job Attempt의 `SUCCESS`, `FAILED`, `TIMED_OUT`, `ABANDONED` 전환
- 검색 가능 Version 교체와 기존 Version Embedding의 `STALE` 전환
- `EMBEDDING_FAILED`, `INDEXED`, `FAILED`, `RETRY` 이벤트 기록
- Lease 연장, 만료 Job 회수와 자동 재Claim
- Batch Embedding API, 병렬 호출, Streaming Insert
- Chunk별 부분 저장과 Checkpoint 재개
- 모델별 가변 Vector 컬럼 또는 다중 Dimension 동시 지원
- Vector 정규화, Quantization과 추가 ANN Index 변경
- 일반 사용자 API에서 Chunk Text나 Vector를 조회하는 기능

이번 범위는 “모든 Chunk의 Vector Set이 저장돼 다음 인덱싱 완료 단계로 넘어갈 수 있는 상태”까지다.
검색 가능 상태 확정은 별도 완료 기능이 담당한다.

## 3. 기준선

- GitHub Issue: `#82`
- Branch: `feature/82`
- 기준 `develop` Commit: `41014da5be3903013892acda1da95617cea5a53c`
- Framework: Spring Boot 3.5.16
- Language: Java 17
- DB: PostgreSQL/OpenSQL, pgvector, Flyway
- 기본 Model: `BAAI/bge-m3`, Dimension `1024`

선행 기능은 다음 계약을 제공한다.

- Job Claim은 Worker, Claim Token과 Lease를 `EmbeddingJob`에 기록한다.
- Attempt 시작은 Job·Worker·Claim Token을 `STARTED` Attempt로 연결한다.
- Chunk 저장은 Version에 0부터 연속된 불변 Chunk Set을 만들고 상태를 `CHUNKED`로 전환한다.
- 후속 단계는 Job을 먼저 잠그고 현재 Claim과 Attempt를 검증한다.
- `embeddings.vector`는 `vector(1024)`이며 `(chunk_id, embedding_model_id)`가 유일하다.

## 4. 핵심 결정

### 4.1 Job 고정 Model 사용

문서 Embedding은 실행 시점의 활성 Model을 다시 조회하지 않는다.

`EmbeddingJob`은 업로드 접수 시점에 사용할 Model을 고정한다. 처리 도중 활성 Model이 교체돼도 이미
접수된 Job의 Chunk와 검색 Query가 다른 Vector 공간에 들어가지 않도록 Job의 Model ID와 Dimension을
사용한다.

Job에 Model 연관이나 ID가 없거나 Dimension이 양수가 아니면 사용자 입력 오류가 아니라 내부 설정
모순으로 처리한다.

### 4.2 API 경로

Endpoint는 다음 경로를 사용한다.

```text
POST /admin/indexing-jobs/{jobId}/attempts/{attemptId}/embeddings
```

Attempt ID를 Path에 포함해 현재 Claim 세대의 실행이라는 점을 명시한다. 기존 애플리케이션은 `/api`
Prefix 없이 `/admin/**`를 사용하며, 기존 ADMIN Security 정책을 그대로 적용한다.

### 4.3 준비·외부 호출·완료 분리

하나의 긴 Transaction 안에서 외부 Embedding Server를 호출하지 않는다.

```text
준비 Transaction
  → Transaction 밖 단건 순차 HTTP 호출
  → 완료 Transaction
```

준비와 완료 Transaction 모두 Job→Version 순서로 잠근다. 두 Transaction 사이에는 ID, Text, Hash,
Dimension만 가진 불변 Snapshot과 Draft만 전달하며 JPA Entity를 전달하지 않는다.

### 4.4 전체 Set 단위 저장

MVP는 한 Version의 모든 Chunk Vector를 메모리에서 생성한 후 한 번에 저장한다.

- 중간 외부 호출이 실패하면 완료 Transaction을 호출하지 않는다.
- 완료 저장 중 한 Row라도 실패하면 전체 Insert를 Rollback한다.
- 부분 저장 상태는 정상 재개 지점이 아니라 내부 데이터 모순이다.
- Chunk 수만큼 전부 저장됐을 때만 완료 재생으로 인정한다.

대용량 문서의 Batch·Checkpoint는 별도 저장 상태와 재개 정책이 필요하므로 후속 범위로 분리한다.

### 4.5 단건 순차 외부 호출

Chunk는 `chunk_index` 오름차순으로 한 건씩 호출한다.

- 먼저 실패한 Chunk 이후의 호출은 실행하지 않는다.
- 호출 순서와 저장 순서가 Chunk Index와 일치한다.
- Text와 Vector를 로그에 남기지 않는다.
- 외부 전송 오류는 `EMBEDDING_SERVER_UNAVAILABLE`로 변환한다.

현재 외부 계약은 `POST /embed` 단건 요청이다. Batch와 병렬 처리 없이 가장 작은 재현 가능한 실행
단위를 유지한다.

### 4.6 상태 경계

정상 상태 변화는 Version에만 적용한다.

| 대상 | 처리 전 | 준비 후 | Vector 저장 후 |
| --- | --- | --- | --- |
| `EmbeddingJob` | `PROCESSING` | `PROCESSING` | `PROCESSING` |
| `EmbeddingJobAttempt` | `STARTED` | `STARTED` | `STARTED` |
| `DocumentVersion` | `CHUNKED` | `EMBEDDING` | `EMBEDDING` |
| `Document` | 기존 상태 | 변경 없음 | 변경 없음 |

Version을 `INDEXED`로 바꾸지 않는 이유는 Vector 저장과 검색 가능 Version 교체가 서로 다른 원자성
경계를 갖기 때문이다. 다음 단계는 Embedding 전체 존재를 다시 검증한 뒤 Job, Attempt, Version,
Document와 검색 가시성을 함께 확정해야 한다.

### 4.7 Vector Hash

Vector Hash는 다음 규칙으로 계산한다.

1. Vector의 각 `float`를 순서대로 IEEE 754 32-bit 값으로 취급한다.
2. 각 값을 big-endian 4 Byte로 직렬화한다.
3. 전체 Byte 배열에 SHA-256을 적용한다.
4. 소문자 64자리 Hex 문자열로 저장한다.

외부 호출 직후와 완료 저장 직전에 같은 규칙을 사용한다. Draft의 Vector나 Hash가 단계 사이에
변경되면 저장을 거부한다.

## 5. 불변식

### 5.1 실행 소유권

준비와 완료 Transaction은 다음 조건을 모두 확인한다.

1. Job이 존재하고 `PROCESSING` 상태다.
2. Job의 현재 Worker, Claim Token, Lock 시작과 Lease 만료 값이 존재한다.
3. 요청 Worker와 Claim Token이 현재 Job 소유권과 같다.
4. 잠금 획득 후 현재 시각이 Lease 만료 시각보다 이르다.
5. Job과 Claim Token으로 조회한 Attempt가 존재한다.
6. Attempt ID가 Path의 Attempt ID와 같다.
7. Attempt Worker가 요청 Worker와 같다.
8. Attempt 상태가 `STARTED`다.

외부 호출 중 Lease가 만료되거나 Claim 세대가 바뀌면 완료 Transaction에서 저장을 거부한다.

### 5.2 Lock 순서

모든 상태 변경 경로는 다음 순서를 유지한다.

1. Embedding Job 쓰기 행 잠금
2. 잠금 후 현재 시각 계산
3. Job 소유권과 Lease 검증
4. 현재 Claim Attempt 검증
5. Job이 참조하는 Document Version 쓰기 행 잠금
6. Chunk와 Embedding 저장 상태 검증

Attempt나 Version을 먼저 잠근 뒤 Job을 잠그는 반대 순서를 만들지 않는다.

### 5.3 Chunk Set

- Chunk 목록은 비어 있을 수 없다.
- Chunk ID와 Version ID가 존재한다.
- 모든 Chunk는 Job Version을 참조한다.
- `chunk_index`는 0부터 끊김 없이 증가한다.
- `chunk_text`는 비어 있지 않다.
- `content_hash`는 소문자 SHA-256 64자리다.
- 준비 Snapshot과 완료 시점의 ID, Index, Text, Hash가 모두 같다.

Chunk는 선행 단계에서 확정된 불변 데이터다. 완료 시 달라졌다면 새 데이터를 조용히 사용하지 않고
내부 모순으로 처리한다.

### 5.4 Vector Draft

- Draft 수는 Chunk 수와 정확히 같다.
- Draft 순서는 Chunk Index 순서와 같다.
- Draft의 Chunk ID, Index, Content Hash는 현재 Chunk와 같다.
- Vector는 null이 아니고 Job Model Dimension과 같다.
- 모든 원소는 유한 값이며 `NaN`, 양·음의 무한대를 포함하지 않는다.
- `vector_hash`는 소문자 64자리 Hex다.
- 저장 직전 다시 계산한 Hash가 Draft Hash와 같다.

Vector 배열은 Generator, Draft와 Entity 경계에서 복사해 호출자가 보관한 배열 변경이 저장 값에
전파되지 않게 한다.

### 5.5 영속 데이터

각 `Embedding`은 다음 관계를 모두 가진다.

- `chunk`: Vector 원본 Chunk
- `documentVersion`: Job 대상 Version
- `document`: Version이 속한 Document
- `embeddingModel`: Job에 고정된 Model
- `dimension`: Model Dimension
- `status`: `ACTIVE`
- `vectorHash`: 검증된 Vector Hash

역정규화한 `document_id`, `document_version_id`는 Chunk 관계에서 도출한 값과 같아야 한다.
`(chunk_id, embedding_model_id)` Unique 제약은 Application 잠금 외의 최종 중복 방어선이다.

## 6. 실행 상태 판정

Version 상태와 Job Model의 저장 개수를 함께 판단한다.

| Version 상태 | 현재 Model Embedding 수 | 처리 |
| --- | ---: | --- |
| `CHUNKED` | 0 | 최초 작업 시작, `EMBEDDING` 전이와 시작 이벤트 |
| `CHUNKED` | 1 이상 | 상태·데이터 모순 |
| `EMBEDDING` | 0 | 외부 호출 실패 또는 중단 이후 처음부터 재개 |
| `EMBEDDING` | Chunk 수와 같음 | 완료 재생 |
| `EMBEDDING` | 0과 Chunk 수 사이 | 부분 저장 모순 |
| 다른 상태 | 0 | 현재 단계 실행 불가 |
| 다른 상태 | 1 이상 | 상태·데이터 모순 |

`EMBEDDING` 상태의 0개 재개는 첫 Transaction Commit 뒤 Process가 종료되거나 외부 서버 오류가 발생한
경우를 복구한다. 부분 저장은 이 설계에서 발생할 수 없으므로 별도 오류로 드러낸다.

## 7. 전체 흐름

### 7.1 최초 정상 요청

1. Controller가 양수 Job·Attempt ID와 Worker ID, canonical Claim Token을 검증한다.
2. 비 Transaction `DocumentEmbeddingService`가 준비 Transaction을 호출한다.
3. 준비 Transaction이 Job을 잠그고 현재 Claim, Lease와 Attempt를 검증한다.
4. Job Model을 고정하고 Job Version을 잠근다.
5. Chunk를 Index 순서로 읽고 전체 Set을 검증한다.
6. 현재 Model Embedding 수가 0인지 확인한다.
7. Version을 `CHUNKED`에서 `EMBEDDING`으로 전환한다.
8. 같은 Transaction에 `EMBEDDING_STARTED` 이벤트를 한 번 저장한다.
9. Version ID, Model ID, Dimension과 Chunk Snapshot을 반환하고 Commit한다.
10. DB 행 잠금이 해제된 뒤 Generator가 Chunk Text를 순서대로 외부 서버에 전달한다.
11. 각 Vector의 차원과 유한 값을 검증하고 Hash를 계산해 Draft를 만든다.
12. 모든 Chunk 호출이 성공하면 완료 Transaction을 호출한다.
13. 완료 Transaction이 Job을 다시 잠그고 Claim, Lease와 Attempt를 다시 검증한다.
14. 같은 Model과 Version인지 확인하고 Version을 잠근다.
15. Chunk Set과 준비 Snapshot이 같은지 다시 확인한다.
16. 다른 요청의 선행 완료와 부분 저장 여부를 확인한다.
17. 모든 Draft와 Vector Hash를 다시 검증한다.
18. 같은 Version·Model의 `ACTIVE` Embedding Set을 `saveAllAndFlush`로 저장한다.
19. Version은 `EMBEDDING`으로 유지하고 Commit한다.
20. Controller가 결과를 `201 Created`로 반환한다.

### 7.2 완료 재생

1. 준비 Transaction에서 소유권과 Attempt를 먼저 검증한다.
2. Version이 `EMBEDDING`이고 현재 Model Embedding 수가 Chunk 수와 같으면 완료 결과를 반환한다.
3. 외부 Embedding Server와 완료 Transaction은 호출하지 않는다.
4. 새 Row와 새 이벤트를 만들지 않는다.
5. Controller는 같은 응답 Body를 `200 OK`로 반환한다.

### 7.3 외부 호출 실패

1. 준비 Transaction의 `EMBEDDING` 전이와 시작 이벤트는 이미 Commit됐다.
2. 외부 서버 장애, 차원 불일치 또는 비유한 Vector가 발생하면 나머지 호출을 중단한다.
3. 완료 Transaction은 호출하지 않는다.
4. Embedding Row는 하나도 저장되지 않는다.
5. Version은 `EMBEDDING`, Job은 `PROCESSING`, Attempt는 `STARTED`로 남는다.
6. 현재 또는 새 유효 Attempt가 같은 Endpoint를 호출해 처음부터 재개할 수 있다.

실패 상태와 Attempt 종료는 후속 실패 처리 기능이 담당한다.

### 7.4 동시 중복 요청

1. 두 요청의 준비 Transaction은 Job 잠금으로 차례로 실행된다.
2. 첫 요청만 `CHUNKED`에서 `EMBEDDING`으로 전환하고 시작 이벤트를 저장한다.
3. 두 요청 모두 저장 결과가 0개인 동안 Transaction 밖에서 같은 Vector Set을 계산할 수 있다.
4. 완료 Transaction은 Job과 Version 잠금으로 다시 직렬화된다.
5. 먼저 진입한 요청이 전체 Embedding Set을 Commit하고 `201 Created`를 반환한다.
6. 나중 요청은 전체 저장을 확인하고 Insert 없이 재생해 `200 OK`를 반환한다.
7. DB에는 Embedding Set 하나와 시작 이벤트 하나만 남는다.

## 8. 계층별 책임

### 8.1 `EmbeddingClient`

- `/embed` 단건 HTTP 전송
- 외부 전송 오류 변환
- Model 선택과 Dimension 검증은 수행하지 않음
- Text와 Vector를 로그에 기록하지 않음

Query Embedding은 활성 Model을 선택하고, 문서 Embedding은 Job 고정 Model을 선택한 뒤 같은 Client를
재사용한다.

### 8.2 `DocumentEmbeddingGenerator`

- 정렬된 Chunk Snapshot 순차 처리
- Vector 차원·유한 값 검증
- Vector Hash 생성
- 완료 Transaction용 불변 Draft 반환
- DB와 JPA Entity에 의존하지 않음

### 8.3 `DocumentEmbeddingTransactionService`

- Job→Version 잠금 순서
- Claim, Lease와 Attempt 검증
- Version·Model·Chunk·기존 Embedding 상태 판정
- 최초 `EMBEDDING` 전이와 시작 이벤트
- 완료 시 Snapshot과 Draft 재검증
- 전체 Embedding Set 원자 저장

### 8.4 `DocumentEmbeddingService`

- 준비→외부 호출→완료 순서 조정
- 완료 재생 시 외부 호출 생략
- 외부 실패 시 완료 Transaction 미호출
- 내부 완료 결과를 API 응답으로 변환
- 자체 DB Transaction 없음

### 8.5 `IndexingJobAdminController`

- Path와 Request Body Validation
- Orchestration 호출
- 최초 저장 `201 Created`, 재생 `200 OK` 선택
- Claim Token, Chunk Text와 Vector 비노출

## 9. API 계약

### 9.1 요청

```json
{
  "workerId": 1,
  "claimToken": "34c19d16-6ae1-4f6a-a35d-0123456789ab"
}
```

- `jobId`, `attemptId`, `workerId`는 양수다.
- Claim Token은 소문자 canonical UUID 형식이며 최대 36자다.

### 9.2 응답

```json
{
  "success": true,
  "status": 201,
  "data": {
    "jobId": 10,
    "attemptId": 100,
    "documentVersionId": 5,
    "embeddingModelId": 7,
    "chunkCount": 3,
    "embeddingCount": 3,
    "versionStatus": "EMBEDDING"
  }
}
```

응답에는 Claim Token, Chunk Text, Vector와 Vector Hash를 포함하지 않는다.

## 10. 오류 계약

| 상황 | HTTP | Error Code |
| --- | ---: | --- |
| Job 없음 | 404 | `EMBEDDING-JOB-001` |
| Job이 `PROCESSING`이 아님 | 409 | `EMBEDDING-JOB-002` |
| Worker·Claim Token 불일치 | 409 | `EMBEDDING-JOB-003` |
| Lease 만료 | 409 | `EMBEDDING-JOB-004` |
| Job 소유권 데이터 모순 | 500 | `EMBEDDING-JOB-005` |
| Attempt 불일치 | 409 | `EMBEDDING-JOB-006` |
| Version 상태에서 실행 불가 | 409 | `DOCUMENT-VERSION-006` |
| Chunk Set 불일치 | 500 | `DOCUMENT-CHUNK-001` |
| Version·Embedding Set 불일치 | 500 | `DOCUMENT-EMBEDDING-001` |
| Vector 또는 Hash가 유효하지 않음 | 500 | `DOCUMENT-EMBEDDING-002` |
| Job Model 없음 | 500 | `EMBEDDING-MODEL-001` |
| 외부 서버 장애 | 503 | `SEARCH-001` |
| Vector 차원 불일치 | 500 | `SEARCH-002` |

## 11. 보안과 운영 주의사항

- Endpoint는 기존 `/admin/**` ADMIN 권한 정책을 사용한다.
- Claim Token은 요청 소유권 검증에만 사용하고 응답, 이벤트와 로그에 남기지 않는다.
- Chunk Text와 Vector는 로그, 오류 메시지와 Metric Label에 넣지 않는다.
- 외부 오류 로그에는 예외 종류만 기록한다.
- 새 Secret이나 Model 설정을 `application.yml`에 하드코딩하지 않는다.
- Entity를 Controller 응답으로 직접 노출하지 않는다.
- `EMBEDDING` 상태와 전체 Row 존재만으로 검색 가능하다고 판단하지 않는다.

## 12. 후속 확장 조건

다음 기능은 별도 설계와 상태 계약이 필요하다.

- 인덱싱 완료 Transaction과 `INDEXED` 전환
- 새 Version 활성화와 이전 Embedding `STALE` 처리
- Job·Attempt 성공 및 실패 종료
- Lease 연장과 만료 Job Recovery
- Batch Embedding API와 호출 병렬화
- Chunk 단위 Checkpoint, 부분 재시도와 대용량 메모리 제한
- 다중 Model Dimension을 위한 물리 Vector 저장 전략
- Vector 생성 처리량·Latency Metric과 실패율 Dashboard

Batch나 Checkpoint를 도입할 때는 현재의 “부분 저장은 모순” 계약을 그대로 유지할 수 없다. 저장
상태, 재개 Cursor, 중복 방지 Key와 실패 복구 정책을 먼저 정의해야 한다.
