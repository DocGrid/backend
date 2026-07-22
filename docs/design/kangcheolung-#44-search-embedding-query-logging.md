# #44 검색 블록 — 질문 임베딩 + 검색 요청 로깅 (F-SEARCH-01/02/03)

closes #44

## 배경

벡터 검색의 첫 단계는 사용자의 검색어를 임베딩 모델과 동일한 차원의 벡터로 변환하는 것이다.
이번 이슈에서는 Python 사이드카 서버(`/embed`)를 호출해 질문을 1024차원 벡터로 변환하는 서비스(F-SEARCH-01/02)와,
검색 요청 자체를 `search_queries` 테이블에 `PROCESSING → SUCCESS/FAILED` 흐름으로 로그 남기는 서비스(F-SEARCH-03)를 구현한다.

이 이슈는 서비스 레이어 "부품" 구현에 집중하며, `POST /search` API 조립은 Issue 5에서 진행한다.

---

## 작업 내용

### 1. 기존 파일 수정

#### `ResultStatus.java`

`PROCESSING` 값 추가. 검색 요청 저장 시 초기 상태로 사용한다.

```java
public enum ResultStatus {
    PROCESSING,  // 추가
    SUCCESS,
    FAILED
}
```

DB는 `VARCHAR(20)` CHECK 없음 → Flyway 마이그레이션 불필요.

#### `ErrorCode.java`

검색 블록 에러 코드 2종 추가.

| 코드 | HTTP | 설명 |
|------|------|------|
| `EMBEDDING_SERVER_UNAVAILABLE` | 503 | Python 서버 타임아웃/연결 실패 |
| `EMBEDDING_DIMENSION_MISMATCH` | 500 | 응답 차원 ≠ 모델 설정 차원 |

#### `application.yml`

```yaml
embedding:
  server:
    base-url: ${EMBEDDING_SERVER_URL:http://localhost:8000}
```

#### `SearchQuery.java`

dirty checking 기반 상태 갱신 메서드 추가.

```java
public void updateToSuccess(int latencyMs) {
    this.status = ResultStatus.SUCCESS;
    this.latencyMs = latencyMs;
}

public void updateToFailed(String errorMessage) {
    this.status = ResultStatus.FAILED;
    this.errorMessage = errorMessage;
}
```

명시적 `save()` 없이 트랜잭션 종료 시점에 dirty checking으로 자동 반영된다.

---

### 2. EmbeddingServerConfig — RestClient Bean 등록

`global/config/EmbeddingServerConfig.java`

```java
@Bean("embeddingRestClient")
public RestClient embeddingRestClient() { ... }
```

- JDK HttpClient 기반, connectTimeout = readTimeout = 5s
- `@Qualifier("embeddingRestClient")`로 주입해 다른 RestClient Bean과 충돌 방지

---

### 3. Python 서버 통신 DTO

| 클래스 | 역할 |
|--------|------|
| `EmbedRequest(String text)` | `POST /embed` 요청 바디 |
| `EmbedServerResponse(float[] vector, int dimension)` | 응답 파싱 |
| `EmbedResult(EmbeddingModel model, float[] vector)` | 서비스 간 전달용 내부 record |

`EmbedResult`는 Issue 5에서 `SearchQueryCommandService.createProcessing()`에 그대로 전달된다.

---

### 4. QueryEmbeddingService (F-SEARCH-01/02)

`domain/embedding/service/query/QueryEmbeddingService.java`

**`embed(String text)` 처리 흐름:**

```
1. EmbeddingModelQueryService.getActiveModel()
   └─ 0건 → EMBEDDING_MODEL_NOT_CONFIGURED (500) ← 기존 구현 재사용
   └─ 2건 이상 → MULTIPLE_ACTIVE_EMBEDDING_MODELS (500) ← 기존 구현 재사용

2. POST /embed { "text": text } 호출
   └─ RestClientException (타임아웃/연결 실패) → EMBEDDING_SERVER_UNAVAILABLE (503)

3. 응답 차원 검증
   └─ response.dimension() != model.getDimension() → EMBEDDING_DIMENSION_MISMATCH (500)

4. EmbedResult(model, vector) 반환
```

- `@Transactional` 없음 — DB 접근 없이 외부 HTTP 호출만 수행
- `RestClientException`으로 타임아웃/연결 거부를 통합 처리 → Spring 본체 격리 보장

---

### 5. SearchQueryCommandService (F-SEARCH-03)

`domain/search/service/command/SearchQueryCommandService.java`

#### `createProcessing()`

```
- user, collection(nullable), queryText, model, vector, topK 받아서
- searchType = VECTOR 고정 (MVP)
- status = PROCESSING 으로 save
- 저장된 SearchQuery 반환 (query_id가 이후 search_results / RAG 블록의 부모 키)
```

#### `markSuccess(SearchQuery, int latencyMs)`

```
- status → SUCCESS
- latencyMs 갱신
- dirty checking 자동 반영 (명시적 save 없음)
```

#### `markFailed(SearchQuery, String errorMessage)`

```
- status → FAILED
- errorMessage 갱신
- dirty checking 자동 반영
- 임베딩 서버 장애, 권한 오류 등 검색 전 단계 실패도 여기서 처리
```

---

## 에러 케이스 정리

| 상황 | 예외 | HTTP |
|------|------|------|
| active 임베딩 모델 없음 | `EMBEDDING_MODEL_NOT_CONFIGURED` | 500 |
| active 임베딩 모델 2개 이상 | `MULTIPLE_ACTIVE_EMBEDDING_MODELS` | 500 |
| Python 서버 타임아웃/연결 실패 | `EMBEDDING_SERVER_UNAVAILABLE` | 503 |
| 응답 차원 불일치 | `EMBEDDING_DIMENSION_MISMATCH` | 500 |
| 빈 검색어 | `@NotBlank` 검증 (Controller 레이어, Issue 5에서 처리) | 400 |

---

## 설계 결정

**`EmbedResult` 내부 record 분리**
`QueryEmbeddingService`가 `(model, vector)`를 함께 반환하도록 설계했다.
Issue 5에서 `SearchQueryCommandService.createProcessing()`을 호출할 때 model과 vector를 분리 전달하지 않아도 되므로 조립 코드가 단순해진다.

**`markSuccess` / `markFailed`에 명시적 save 없음**
`SearchQueryCommandService`는 클래스 레벨 `@Transactional`이고, `SearchQuery`는 이미 영속 상태이므로 dirty checking으로 충분하다.
신규 엔티티 생성(`createProcessing`)에만 `save()`를 사용한다.

**503 격리 원칙**
Python 서버 장애 시 `EMBEDDING_SERVER_UNAVAILABLE(503)`로 응답하되, Spring 애플리케이션 자체는 정상 동작을 유지한다.
`RestClientException`을 try-catch로 잡아 DocGridException으로 변환하는 방식으로 격리한다.

**`search_type = VECTOR` 고정**
MVP는 dense vector 검색만 지원한다. `KEYWORD`, `HYBRID`는 Issue 명세상 2단계 확장 예정이므로 현재는 상수로 고정한다.
