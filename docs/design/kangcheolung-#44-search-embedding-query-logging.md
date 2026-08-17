# #44 검색 블록 — 질문 임베딩 + 검색 요청 로깅 (F-SEARCH-01/02/03)

closes #44

---

## 배경

벡터 검색의 첫 단계는 사용자의 검색어를 임베딩 모델과 동일한 차원의 벡터로 변환하는 것이다. 이번 이슈에서는 두 가지를 구현한다.

- **F-SEARCH-01/02**: Python 사이드카 서버(`POST /embed`)를 호출해 질문을 1024차원 벡터로 변환
- **F-SEARCH-03**: 검색 요청 자체를 `search_queries` 테이블에 `PROCESSING → SUCCESS/FAILED` 흐름으로 기록

이 이슈는 서비스 레이어 "부품" 구현에 집중하며, `POST /search` API 조립은 Issue 5(#56)에서 진행한다.

---

## 1. 기존 파일 수정

### ResultStatus.java — PROCESSING 추가

```java
public enum ResultStatus {
    PROCESSING,  // 추가 — 검색 요청 저장 시 초기 상태
    SUCCESS,
    FAILED
}
```

DB는 `VARCHAR(20)` CHECK 없음 → Flyway 마이그레이션 불필요.

### ErrorCode.java — 검색 블록 에러 코드 2종 추가

| 코드 | HTTP | 설명 |
|---|---|---|
| `EMBEDDING_SERVER_UNAVAILABLE` | 503 | Python 서버 타임아웃/연결 실패 |
| `EMBEDDING_DIMENSION_MISMATCH` | 500 | 응답 차원 ≠ 모델 설정 차원 |

### application.yml — 임베딩 서버 URL 설정

```yaml
embedding:
  server:
    base-url: ${EMBEDDING_SERVER_URL:http://localhost:8000}
```

### SearchQuery.java — 상태 갱신 메서드 추가

상태 변경 로직을 엔티티 안에 캡슐화 — JPA 더티체킹으로 트랜잭션 커밋 시 자동 UPDATE.

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

명시적 `save()` 없이 트랜잭션 종료 시점에 더티체킹으로 자동 반영.

---

## 2. EmbeddingServerConfig.java

**한 줄 요약**: Python 임베딩 서버를 호출할 RestClient를 만들어주는 설정 클래스.

```java
@Configuration
public class EmbeddingServerConfig {

    @Value("${embedding.server.base-url}")
    private String baseUrl;

    @Bean("embeddingRestClient")
    public RestClient embeddingRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
    }
}
```

- JDK 11+ 내장 `java.net.http.HttpClient` 사용 — 별도 라이브러리 의존성 없음.
- connect timeout(연결 시도)과 read timeout(응답 대기)을 분리 설정 — "서버가 안 켜져 있는 것"과 "서버는 켜져 있는데 느린 것"을 구분.
- `@Bean("embeddingRestClient")`: 프로젝트에 다른 RestClient Bean이 있을 수 있어 이름으로 구분.

---

## 3. Python 서버 통신 DTO

| 클래스 | 역할 |
|---|---|
| `EmbedRequest(String text)` | `POST /embed` 요청 바디 |
| `EmbedServerResponse(float[] vector)` | 응답 파싱 — Python 서버는 `{"vector": [...]}` 형태만 반환 |
| `EmbedResult(EmbeddingModel model, float[] vector)` | 서비스 간 전달용 내부 record |

`EmbedResult`는 Issue 5에서 `SearchQueryCommandService.createProcessing()`에 그대로 전달된다.

---

## 4. QueryEmbeddingService.java (F-SEARCH-01/02)

**한 줄 요약**: 질문 텍스트를 Python 임베딩 서버에 보내 벡터로 변환하고, 응답을 검증하는 서비스.

```java
@Slf4j
@Service
public class QueryEmbeddingService {

    private final EmbeddingModelQueryService embeddingModelQueryService;
    private final RestClient restClient;

    public QueryEmbeddingService(
        EmbeddingModelQueryService embeddingModelQueryService,
        @Qualifier("embeddingRestClient") RestClient restClient
    ) {
        this.embeddingModelQueryService = embeddingModelQueryService;
        this.restClient = restClient;
    }

    public EmbedResult embed(String text) {
        EmbeddingModel activeModel = embeddingModelQueryService.getActiveModel();

        EmbedServerResponse response;
        try {
            response = restClient.post()
                .uri("/embed")
                .body(new EmbedRequest(text))
                .retrieve()
                .body(EmbedServerResponse.class);
        } catch (RestClientException e) {
            log.error("임베딩 서버 호출 실패: {}", e.getMessage());
            throw new DocGridException(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE);
        }

        if (response == null
                || response.vector() == null
                || response.vector().length != activeModel.getDimension()) {
            int actual = (response == null || response.vector() == null) ? -1 : response.vector().length;
            log.error("임베딩 차원 불일치: expected={}, actual={}", activeModel.getDimension(), actual);
            throw new DocGridException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
        }

        return new EmbedResult(activeModel, response.vector());
    }
}
```

**`embed(String text)` 처리 흐름:**

```
1. EmbeddingModelQueryService.getActiveModel()
   └─ 0건 → EMBEDDING_MODEL_NOT_CONFIGURED (500) ← 기존 구현 재사용
   └─ 2건 이상 → MULTIPLE_ACTIVE_EMBEDDING_MODELS (500) ← 기존 구현 재사용

2. POST /embed { "text": text } 호출
   └─ RestClientException (타임아웃/연결 실패) → EMBEDDING_SERVER_UNAVAILABLE (503)

3. 3중 검증: response null / vector null / 배열 길이 != 모델 차원
   └─ 불일치 → EMBEDDING_DIMENSION_MISMATCH (500)

4. EmbedResult(model, vector) 반환
```

**Java ↔ Python 실제 통신 예시**

Spring이 Python 서버로 보내는 요청:
```json
{ "text": "휴가 신청 절차 알려줘" }
```

Python 서버가 돌려주는 응답:
```json
{ "vector": [0.02143, -0.44201, ...] }
```

> **버그 수정 이력 (Issue 5 수동 테스트 중 발견)**
>
> 초기 버전의 `EmbedServerResponse`는 `float[] vector`와 함께 `int dimension` 필드를 선언해 두었고,
> `response.dimension() != activeModel.getDimension()` 검증을 추가로 수행했다.
> 그러나 Python `/embed` 서버 응답(`{"vector": [...]}`)에는 `dimension` 필드가 없어서,
> Java가 `int` 기본값 `0`으로 역직렬화 → `0 != 1024`가 항상 참이 되어 **모든 요청이 무조건 EMBEDDING_DIMENSION_MISMATCH로 실패**하는 버그가 있었다.
> `vector().length`로 이미 실제 차원을 검증하고 있어 `dimension()` 체크는 중복이었으므로,
> `EmbedServerResponse`에서 `dimension` 필드 자체를 제거하고 검증을 3중으로 단순화해 해결.

설계 포인트:
- `@Transactional` 없음 — DB 접근 없이 외부 HTTP 호출만 수행.
- Python 서버 장애가 Spring 본체 크래시로 번지지 않도록 `RestClientException`을 try-catch로 잡아 `DocGridException`으로 변환 후 격리.

---

## 5. SearchQueryCommandService.java (F-SEARCH-03)

**한 줄 요약**: 검색 요청을 `PROCESSING → SUCCESS/FAILED` 상태로 `search_queries`에 기록하는 서비스.

```java
@Transactional
@Service
@RequiredArgsConstructor
public class SearchQueryCommandService {

    private final SearchQueryRepository searchQueryRepository;

    public SearchQuery createProcessing(
        User user, DocumentCollection collection, String queryText,
        EmbeddingModel model, float[] vector, int topK
    ) {
        SearchQuery searchQuery = SearchQuery.builder()
            .user(user).collection(collection).queryText(queryText)
            .queryEmbeddingModel(model).queryVector(vector)
            .searchType(SearchType.VECTOR).topK(topK)
            .status(ResultStatus.PROCESSING)
            .build();
        return searchQueryRepository.save(searchQuery);
    }

    public void markSuccess(SearchQuery searchQuery, int latencyMs) {
        searchQuery.updateToSuccess(latencyMs);
        // save() 명시 없음 — 트랜잭션 안에서 더티체킹이 자동 UPDATE 처리
    }

    // REQUIRES_NEW: Facade에서 예외 재전파로 롤백돼도 FAILED 상태가 독립 트랜잭션으로 저장됨
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(SearchQuery searchQuery, String errorMessage) {
        searchQuery.updateToFailed(errorMessage);
    }
}
```

**`markFailed()`에 REQUIRES_NEW가 필요한 이유:**

클래스 전체가 `@Transactional`이므로, 만약 `markFailed()`도 기본 전파 방식(`REQUIRED`)이었다면 이 UPDATE가 바깥의 큰 트랜잭션(`SearchFacade.search()`) 안에 묶인다. 그 큰 트랜잭션은 예외 때문에 결국 롤백되므로, 방금 기록한 FAILED 상태까지 통째로 사라져 "실패해도 로그는 남는다"는 원칙이 깨진다. `REQUIRES_NEW`는 현재 트랜잭션과 완전히 독립된 새 트랜잭션을 만들어 즉시 커밋하므로, 바깥 트랜잭션이 롤백돼도 FAILED 기록은 보존된다.

**주의(검증 필요):** `createProcessing()`으로 만든 `search_queries` row가 아직 바깥 트랜잭션(REQUIRED, 미커밋) 안에 있는 상태에서, `markFailed()`가 별도 트랜잭션(REQUIRES_NEW, 별도 커넥션)으로 그 미확정 row를 업데이트하려 시도하는 구조다. DB 격리 수준과 락 상황에 따라 블로킹이나 예기치 않은 동작으로 이어질 수 있어, "임베딩 서버 다운 시 markFailed가 실제로 안전하게 기록되는지"를 통합 테스트로 검증해볼 필요가 있음.

---

## 에러 케이스 정리

| 상황 | 예외 | HTTP |
|---|---|---|
| active 임베딩 모델 없음 | `EMBEDDING_MODEL_NOT_CONFIGURED` | 500 |
| active 임베딩 모델 2개 이상 | `MULTIPLE_ACTIVE_EMBEDDING_MODELS` | 500 |
| Python 서버 타임아웃/연결 실패 | `EMBEDDING_SERVER_UNAVAILABLE` | 503 |
| 응답 차원 불일치 | `EMBEDDING_DIMENSION_MISMATCH` | 500 |
| 빈 검색어 | `@NotBlank` 검증 (Controller 레이어, Issue 5에서 처리) | 400 |

---

## 설계 결정 요약

**`EmbedResult` 내부 record 분리**  
`QueryEmbeddingService`가 `(model, vector)`를 함께 반환하도록 설계. Issue 5에서 `createProcessing()` 호출 시 model과 vector를 분리 전달하지 않아도 되므로 조립 코드가 단순해진다.

**`markSuccess` / `markFailed`에 명시적 save 없음**  
`SearchQueryCommandService`는 클래스 레벨 `@Transactional`이고, `SearchQuery`는 이미 영속 상태이므로 더티체킹으로 충분. 신규 엔티티 생성(`createProcessing`)에만 `save()`를 사용.

**503 격리 원칙**  
Python 서버 장애 시 `EMBEDDING_SERVER_UNAVAILABLE(503)`으로 응답하되, Spring 애플리케이션 자체는 정상 동작 유지. `RestClientException`을 try-catch로 잡아 `DocGridException`으로 변환하는 방식으로 격리.

**`search_type = VECTOR` 고정**  
MVP는 dense vector 검색만 지원. `KEYWORD`, `HYBRID`는 2단계 확장 예정이므로 현재는 상수로 고정.

---

## 이후 변경 이력 (원 설계 이후 팀 작업으로 확장된 부분)

위 내용은 #44 시점의 설계·구현 기록으로 그대로 보존한다. 이후 팀 작업으로 아래가 확장되었으며, **원 설계의 핵심 — 검색 5초 예산, connect/read timeout 분리, 3중 차원 검증, `RestClientException` → `DocGridException` 변환을 통한 503 격리 원칙 — 은 현재 구조에서도 그대로 유지되고 있다.**

### RestClient 용도별 분리 (#213 — 김기민)

§2의 단일 `embeddingRestClient`(5s 하드코딩)가 용도별 2개 빈으로 분리되었다. timeout 값도 하드코딩에서 설정값으로 빠졌다.

| Bean | 용도 | 호출 API | read timeout |
|---|---|---|---:|
| `embeddingRestClient` | 검색 단건 (원 설계) | `POST /embed` | 5s (유지) |
| `documentEmbeddingRestClient` | 문서 인덱싱 배치 | `POST /embed/batch` | 30s (실측 p99 기반) |

검색의 5초 예산을 지키면서 문서 배치의 긴 추론 시간만 별도 허용하는 구조 — 원 설계의 "connect/read 분리" 원칙이 "검색/문서 read 예산 분리"로 한 단계 더 확장된 것.

### HTTP 호출의 `EmbeddingClient` 위임 (팀 작업)

§4에서 `QueryEmbeddingService`가 RestClient를 직접 호출하던 부분이 `domain/embedding/client/EmbeddingClient`로 위임되었다. `QueryEmbeddingService`는 활성 모델 조회 + 차원 검증 흐름을 그대로 유지하고, HTTP 호출과 오류 분류만 client 계층으로 이동했다. `/embed`(검색)와 `/embed/batch`(문서)를 한 client가 담당한다.

### 에러 코드 추가 (#216 — 김기민)

에러 케이스 표에 한 종류가 추가되었다.

| 상황 | 예외 | HTTP |
|---|---|---|
| 임베딩 서버 과부하 (동시 실행·대기열 초과) | `EMBEDDING_PROVIDER_OVERLOADED` (SEARCH-003) | 429 |

Python 서버의 Admission Controller가 반환하는 429를 `EmbeddingClient`가 이 코드로 변환한다. 상세는 `gimin-#216-embedding-provider-load-protection.md` 참조.
