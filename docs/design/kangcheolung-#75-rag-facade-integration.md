# #75 RAG 블록 완성 — RagFacade 연결 및 최종 answer + citations 응답 조합 (F-RAG-05)

closes #75

---

## 배경

Issue 1~4에서 `PromptBuilder`, `OllamaClient`, `RagResponseCommandService`, `ResponseCitationCommandService`를 만들었지만, 전부 서로 연결되지 않은 독립 부품이었다. 이번 이슈는 이 4개를 `RagFacade`로 묶고, 기존 검색 엔드포인트(`POST /search`)에 연결해 **RAG 블록(F-RAG-01~05) 전체를 완성**한다.

| Issue | 범위 | 상태 |
|---|---|---|
| 1 | Ollama 로컬 세팅 + PromptBuilder (F-RAG-01) | 완료 |
| 2 | OllamaClient 연동 (F-RAG-02) | 완료 |
| 3 | rag_responses 저장 (F-RAG-03) | 완료 |
| 4 | response_citations 저장 (F-RAG-04) | 완료 |
| **5 (이 문서)** | 최종 answer + citations 응답 조합 (F-RAG-05) | 완료 |

RAG 블록은 명세상 자기만의 엔드포인트가 없다(6장) — 검색 블록의 `POST /search`가 검색 완료 후 내부적으로 RAG를 호출해 하나의 응답으로 조합하는 구조다. 그래서 이번 이슈는 새 API를 추가하는 게 아니라 **기존 검색 엔드포인트를 확장**하는 작업이다.

이 이슈에서 Issue 4가 미뤄뒀던 것도 함께 처리한다: `response_citations.search_result_id`가 지금까지 `null`이었는데, 이번에 실제 값으로 채운다.

---

## 전체 흐름

```text
POST /search
     │
     ▼
SearchController.search()
     │
     ├─ 1. searchFacade.search(userId, request)          ← 자체 트랜잭션, 여기서 커밋까지 끝남
     │       내부: 임베딩 → 권한 pre-filter → 벡터 검색 → live check → search_results 저장
     │       반환: SearchOutcome(response, candidates, savedResults)
     │
     ├─ 2. ragFacade.generate(queryId, queryText, candidates, savedResults)   ← 별도 트랜잭션
     │       │
     │       ├─ candidates 비어있음 → createNoContext() (LLM 호출 생략)
     │       │
     │       └─ candidates 있음
     │             ├─ PromptBuilder.build()          ← Issue 1
     │             ├─ OllamaClient.generate()          ← Issue 2
     │             ├─ RagResponseCommandService.createSuccess/createFailed()  ← Issue 3
     │             └─ ResponseCitationCommandService.saveAll()  ← Issue 4 (search_result_id 채움)
     │       반환: RagAnswer(answerText, citations)
     │
     └─ 3. outcome.response().withAnswer(ragAnswer.answerText(), ragAnswer.citations())
             → 최종 SearchResponse(queryId, results, answer, citations) 반환
```

`SearchFacade`(1번)와 `RagFacade`(2번)는 `SearchController`가 순차 호출하는 별개의 트랜잭션이다 — 검색 DB 작업과, Ollama HTTP 호출(최대 30초)을 포함한 RAG DB 작업이 하나의 커넥션을 오래 물고 있지 않도록 분리했다.

---

## 변경 파일 — 검색 블록

### 1. `domain/search/service/command/SearchResultCommandService.java`

```java
public List<SearchResult> saveAll(SearchQuery searchQuery, List<VectorSearchCandidate> candidates) {
    List<SearchResult> results = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) { ... }
    return searchResultRepository.saveAll(results);
}
```
반환 타입만 `void` → `List<SearchResult>`로 바꿨다. 로직은 그대로다 — `JpaRepository.saveAll()`이 이미 저장된 엔티티 리스트를 반환하므로 그걸 그대로 돌려주기만 하면 된다. **이걸 바꾼 이유**: 저장된 `SearchResult`의 실제 `id`가 있어야 `ResponseCitationCommandService`가 `search_result_id`를 채울 수 있는데, 지금까지는 저장하고 그 결과를 아무도 못 받았다 (Issue 4 설계 문서에서 이미 예고했던 변경).

### 2. `domain/search/dto/SearchOutcome.java` (신규)

```java
public record SearchOutcome(
    SearchResponse response,
    List<VectorSearchCandidate> candidates,
    List<SearchResult> savedResults
) {
}
```
`SearchFacade`가 `SearchController`에게 넘겨주는 **내부 전달용** 객체다(API로 노출되지 않음). `SearchResponse`(API 응답 DTO)는 화면 표시용 `SearchResultItem`만 담고 있어 chunk/document id, 저장된 `SearchResult`의 id가 없다. `RagFacade.generate()` 호출에 이 정보들이 필요해서, `SearchResponse`를 감싸는 wrapper로 따로 만들었다.

### 3. `domain/search/service/SearchFacade.java`

반환 타입이 `SearchResponse` → `SearchOutcome`으로 바뀌었다. 로직 흐름 자체(임베딩 → 권한 필터 → 벡터 검색 → live check → 저장)는 전혀 안 바뀌었고, 마지막에 반환하는 지점 두 군데만 `SearchOutcome`으로 감싼다.

```java
// 접근 가능한 문서 0건일 때
return new SearchOutcome(SearchResponse.empty(searchQuery.getId()), List.of(), List.of());

// 정상 흐름 끝
List<SearchResult> savedResults = searchResultCommandService.saveAll(searchQuery, verified);
...
return new SearchOutcome(SearchResponse.of(searchQuery.getId(), verified), verified, savedResults);
```
두 경우 다 `candidates`가 빈 리스트(`List.of()`)로 귀결되는 걸 주목할 것 — 이 덕분에 `RagFacade`는 "접근 가능 문서 0건"과 "live check로 전부 탈락"을 구분할 필요 없이 `candidates.isEmpty()` 하나로만 NO_CONTEXT를 판단할 수 있다.

### 4. `domain/search/dto/response/SearchResponse.java`

```java
public record SearchResponse(
    Long queryId,
    List<SearchResultItem> results,
    String answer,
    List<CitationResponse> citations
) {
    public static SearchResponse of(Long queryId, List<VectorSearchCandidate> candidates) {
        ...
        return new SearchResponse(queryId, List.copyOf(items), null, List.of());
    }

    public static SearchResponse empty(Long queryId) {
        return new SearchResponse(queryId, List.of(), null, List.of());
    }

    public SearchResponse withAnswer(String answer, List<CitationResponse> citations) {
        return new SearchResponse(queryId, results, answer, citations);
    }
}
```
`answer`/`citations` 필드를 추가했다. 기존 `results` 필드는 그대로 유지했다 — 검색 후보 전체(citations는 그중 실제로 인용된 것만)를 프론트가 계속 볼 수 있어야 한다는 판단 때문이다(하위 호환).

레코드는 불변이라 "일단 `queryId`/`results`만 채워서 만들고, 나중에 `answer`/`citations`를 마저 채우는" 2단계 조립이 필요했다. `of()`/`empty()`는 `answer=null, citations=[]`인 상태로 만들고, `withAnswer()`가 그 두 필드만 갈아끼운 새 레코드를 반환한다. `SearchController`가 `outcome.response().withAnswer(...)`로 최종 병합에 쓴다.

### 5. `domain/search/dto/response/CitationResponse.java` (신규)

```java
public record CitationResponse(
    String label,
    Long documentId,
    String documentTitle,
    Long chunkId,
    Integer pageNo,
    String quotedText
) {
    public static CitationResponse of(int order, VectorSearchCandidate candidate) {
        return new CitationResponse(
            "[" + order + "]", candidate.documentId(), candidate.documentTitle(),
            candidate.chunkId(), candidate.pageNo(), candidate.chunkText()
        );
    }
}
```
API 응답에 노출되는 출처 1건의 모양. 명세 6장의 최종 응답 예시(`{ "label": "[1]", "documentId": 10, ... }`)와 필드를 그대로 맞췄다. `RagFacade`가 갖고 있는 `List<VectorSearchCandidate>`(검색 단계에서 이미 documentId/documentTitle/pageNo/chunkText를 다 갖고 있음)로부터 직접 만들기 때문에 DB 재조회가 없다.

**왜 이 파일을 `domain/search/dto/response/`(RAG 도메인이 아니라 검색 도메인)에 뒀나 — 순환 참조 방지**: `RagFacade`(rag 도메인)는 이미 `domain.search.dto.VectorSearchCandidate`를 참조하고 있어 "RAG → Search" 의존 방향이 이미 성립해 있다(`java-style.md`: 도메인 간 의존은 단방향, 순환 참조 금지). `SearchResponse`(search 도메인)가 `citations` 필드를 가지려면 그 원소 타입이 필요한데, 이걸 `domain/rag/dto/response/`에 두면 "Search → RAG" 역방향 의존이 추가로 생겨 순환 참조가 된다. 그래서 `CitationResponse`를 search 도메인에 두고, rag 도메인의 `RagAnswer`가 이걸 가져다 쓰는 방향(RAG → Search)으로만 의존이 흐르게 했다.

### 6. `domain/search/controller/SearchController.java`

```java
private final SearchFacade searchFacade;
private final RagFacade ragFacade;

@PostMapping
public ResponseEntity<ApiResponse<SearchResponse>> search(
    @Parameter(hidden = true) @CurrentUser Long userId,
    @RequestBody @Valid SearchRequest request
) {
    SearchOutcome outcome = searchFacade.search(userId, request);
    RagAnswer ragAnswer = ragFacade.generate(
        outcome.response().queryId(), request.queryText(), outcome.candidates(), outcome.savedResults()
    );
    return ResponseUtils.ok(outcome.response().withAnswer(ragAnswer.answerText(), ragAnswer.citations()));
}
```
`RagFacade`를 주입받아 검색 완료 후 순차 호출한다. Controller 자체는 `@Transactional`이 아니므로(원래도 아니었음), `searchFacade.search()`가 완전히 끝나고(커밋됨) 나서야 `ragFacade.generate()`가 시작된다 — 이 구조 자체가 두 트랜잭션을 물리적으로 분리한다.

---

## 변경 파일 — RAG 블록

### 7. `domain/rag/service/command/RagResponseCommandService.java` — `createNoContext()` 추가

```java
private static final String NO_CONTEXT_ANSWER_TEXT = "관련 문서를 찾지 못했습니다.";

public RagResponse createNoContext(SearchQuery query) {
    RagResponse ragResponse = RagResponse.builder()
        .query(query)
        .answerText(NO_CONTEXT_ANSWER_TEXT)
        .status(ResultStatus.SUCCESS)
        .build();
    return ragResponseRepository.save(ragResponse);
}
```
검색 결과가 0건이라 LLM을 아예 호출하지 않은 경우를 위한 메서드. `createFailed()`가 이미 쓰고 있는 "고정 문구 상수" 패턴을 그대로 대칭되게 적용했다(`FAILED_ANSWER_TEXT` 옆에 `NO_CONTEXT_ANSWER_TEXT`). `llmProvider`/`llmModelName`/`promptText`는 채우지 않는다 — 실제로 아무것도 호출하지 않았으니 채울 값 자체가 없다. `status`는 `SUCCESS`다 — 이건 실패가 아니라 "검색이 잘 됐는데 결과가 없었다"는 정상적인 결과 케이스이기 때문이다(검색 블록이 접근 가능 문서 0건일 때도 에러가 아니라 빈 배열 200으로 응답하는 것과 동일한 철학).

### 8. `domain/rag/service/command/ResponseCitationCommandService.java` — `search_result_id` 채우기

```java
public void saveAll(RagResponse response, List<VectorSearchCandidate> candidates, List<SearchResult> searchResults) {
    for (int i = 0; i < candidates.size(); i++) {
        ...
        .searchResult(searchResults.get(i))
        ...
    }
}
```
`List<SearchResult> searchResults` 파라미터가 추가됐다. `candidates`와 `searchResults`는 `SearchResultCommandService.saveAll()`이 **동일한 리스트를 동일한 순서로** 순회하며 만든 것이므로, 인덱스로 1:1 대응한다는 전제로 `searchResults.get(i)`를 그대로 FK에 연결한다(주석으로 이 전제를 명시해뒀다). Issue 4에서 `nullable`이라 비워뒀던 부분이 이번에 채워졌다.

---

## 신규 파일 — RAG 블록

### 9. `domain/rag/dto/RagAnswer.java`

```java
public record RagAnswer(String answerText, List<CitationResponse> citations) {
    public static RagAnswer of(String answerText, List<VectorSearchCandidate> candidates) {
        List<CitationResponse> items = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            items.add(CitationResponse.of(i + 1, candidates.get(i)));
        }
        return new RagAnswer(answerText, List.copyOf(items));
    }
    public static RagAnswer noContext(String answerText) {
        return new RagAnswer(answerText, List.of());
    }
}
```
`RagFacade.generate()`의 반환 타입. `SearchOutcome`이 검색 쪽 결과를 담는 그릇이라면, 이건 RAG 쪽 결과를 담는 그릇이다. `citations`는 `ResponseCitationCommandService`가 DB에 저장한 것과 별개로, `candidates`로부터 **직접** 다시 만든다(같은 라벨 규칙 `"[" + order + "]"` 재사용) — DB에 저장한 걸 다시 SELECT해서 응답을 조립하지 않고, 이미 메모리에 있는 값으로 응답도 함께 조립하는 것이다.

### 10. `domain/rag/service/RagFacade.java` — 이번 이슈의 핵심 조율자

```java
@Transactional
@Service
@RequiredArgsConstructor
@Slf4j
public class RagFacade {

    private final PromptBuilder promptBuilder;
    private final OllamaClient ollamaClient;
    private final RagResponseCommandService ragResponseCommandService;
    private final ResponseCitationCommandService responseCitationCommandService;
    private final EntityManager entityManager;

    public RagAnswer generate(
        Long queryId, String queryText, List<VectorSearchCandidate> candidates, List<SearchResult> searchResults
    ) {
        SearchQuery queryRef = entityManager.getReference(SearchQuery.class, queryId);

        // 검색 후보가 없으면(NO_CONTEXT) LLM 호출 없이 고정 응답 저장
        if (candidates.isEmpty()) {
            RagResponse ragResponse = ragResponseCommandService.createNoContext(queryRef);
            log.info("[RAG] no context queryId={} responseId={}", queryId, ragResponse.getId());
            return RagAnswer.noContext(ragResponse.getAnswerText());
        }

        // 검색 후보가 있으면 프롬프트 조립 후 LLM 호출
        String prompt = promptBuilder.build(queryText, candidates);
        try {
            OllamaGenerateResult result = ollamaClient.generate(prompt);
            RagResponse ragResponse = ragResponseCommandService.createSuccess(queryRef, prompt, result);
            responseCitationCommandService.saveAll(ragResponse, candidates, searchResults);
            log.info("[RAG] done queryId={} responseId={} latencyMs={}", queryId, ragResponse.getId(), result.latencyMs());
            return RagAnswer.of(result.answerText(), candidates);
        } catch (DocGridException e) {
            ragResponseCommandService.createFailed(queryRef, prompt, e.getMessage());
            throw e;
        }
    }
}
```

**`entityManager.getReference(SearchQuery.class, queryId)`를 쓰는 이유**: `RagFacade`는 `SearchFacade`와 다른 트랜잭션에서 실행된다. `SearchFacade`가 갖고 있던 진짜 `SearchQuery` 엔티티 객체를 그대로 넘겨받으면 detached 상태 문제가 생길 수 있어서, `queryId`(Long)만 받아 이 트랜잭션 안에서 새로 프록시를 만든다. `SearchResultCommandService`가 `DocumentChunk`/`Embedding` FK에 이미 쓰고 있는 것과 동일한 기법이다.

**`@Transactional`을 클래스에 붙인 이유**: `createSuccess()`(또는 `createNoContext()`)와 `saveAll()`(citation 저장)이 하나의 원자적 단위로 묶이길 원했다 — 답변은 저장됐는데 출처 저장이 실패해서 어중간하게 남는 상황을 피하기 위함이다. `SearchFacade`와는 별개의 트랜잭션이므로(Context 문단 참고), 검색 DB 작업과 섞이지 않는다. Ollama HTTP 호출이 이 트랜잭션 안에 포함되는 것 자체는 `SearchFacade`가 임베딩 HTTP 호출을 트랜잭션에 포함하는 것과 동일한 기존 트레이드오프를 그대로 따른다(MVP 단계 단순성 우선, 두 설계 문서 모두에 명시된 남은 이슈).

**실패 시 `createFailed()` 후 예외 재전파**: `catch (DocGridException e)`에서 실패 기록을 남기고 예외를 그대로 다시 던진다. `RagFacade`는 HTTP 상태 코드를 직접 조립하지 않는다 — `GlobalExceptionHandler`가 `RAG_SERVICE_UNAVAILABLE`을 받아 503으로 변환한다. 이때 이미 커밋된 검색 결과(`search_results`)는 별도 트랜잭션(`SearchFacade`)에서 저장된 것이라 영향받지 않고 그대로 남는다.

---

## 로컬 검증 (실제 수행 기록)

```bash
$ ./gradlew compileTestJava
BUILD SUCCESSFUL

$ ./gradlew test   # 전체 테스트 스위트
BUILD SUCCESSFUL

$ ./gradlew build -x test
BUILD SUCCESSFUL
```

기존 검색 블록 테스트(`SearchFacadeTest`, `SearchResultCommandServiceTest`)와 RAG 블록 테스트(`RagResponseCommandServiceTest`, `ResponseCitationCommandServiceTest`)를 이번 이슈의 시그니처 변경에 맞춰 함께 수정했고, 신규 `RagFacadeTest`(NO_CONTEXT/정상/실패 3케이스)를 추가했다. 전체 테스트 스위트가 회귀 없이 통과했다.

실제 문서 업로드/인덱싱 후 `POST /search`를 Swagger로 호출하는 e2e 확인은 별도로 진행 예정이다(이 문서에는 자동화 테스트 결과만 기록).

---

## 에러 케이스 정리

| 상황 | 처리 |
|---|---|
| 검색 자체 실패(임베딩 서버 장애, 사용자/컬렉션 없음 등) | 기존 `SearchFacade`의 에러 처리 그대로(변경 없음) — `RagFacade`는 호출되지도 않음 |
| 접근 가능 문서 0건 / live check로 전부 탈락 | `SearchOutcome.candidates()`가 빈 리스트 → `RagFacade`가 `createNoContext()`로 처리, 200 정상 응답 + 고정 answer 문구 |
| Ollama 호출 실패(타임아웃/연결거부) | `createFailed()`로 FAILED 기록 후 예외 재전파 → 503 `RAG_SERVICE_UNAVAILABLE`. 검색 결과(`search_results`)는 이미 별도 트랜잭션에서 커밋되어 그대로 유지됨 |
| 정상 흐름 | 200 + `results`(검색 후보 전체) + `answer`(LLM 답변) + `citations`(실제 인용된 출처) |

---

## 설계 결정 요약

**`SearchFacade`/`RagFacade` 트랜잭션 분리**: `SearchController`가 두 Facade를 순차 호출하는 구조 자체로 트랜잭션이 물리적으로 나뉜다. LLM HTTP 호출(최대 30초)이 검색 DB 작업과 같은 커넥션을 오래 물고 있지 않도록 하기 위함.

**`SearchOutcome`/`RagAnswer` — API로 노출되지 않는 내부 전달용 레코드 2개 도입**: `SearchFacade`→`Controller`, `RagFacade`→`Controller` 각각의 결과를 담는 그릇을 분리해서, 최종 API 응답(`SearchResponse`)과 내부 처리에 필요한 데이터(원본 후보, 저장된 엔티티)를 섞지 않았다.

**`CitationResponse`를 search 도메인에 배치해 순환 참조 방지**: RAG가 이미 Search에 의존하는 기존 방향을 유지하기 위한 의도적 선택.

**NO_CONTEXT와 FAILED를 대칭적으로 설계**: 둘 다 `RagResponseCommandService`에 고정 문구 상수 + 전용 생성 메서드로 존재한다. 차이는 `status`(SUCCESS vs FAILED)와 트랜잭션 전파(`createNoContext`는 일반 `REQUIRED`, `createFailed`는 `REQUIRES_NEW`) 정도다.

**citations 응답은 DB 재조회 없이 메모리의 `candidates`로부터 재구성**: `ResponseCitationCommandService`가 저장한 것과 `RagAnswer.of()`가 만드는 것은 별개의 객체 생성이지만, 소스(`candidates`)와 라벨 규칙(`"[" + order + "]"`)이 동일해 항상 일치한다.

---

## 남은 이슈 / TODO

### 코드
- `SearchFacade`(임베딩 HTTP 호출 포함)와 `RagFacade`(Ollama HTTP 호출 포함) 둘 다 "트랜잭션 안에 HTTP 호출 포함" 트레이드오프를 갖고 있다 — 두 곳 다 아직 정식으로 트랜잭션 분리 리팩터링은 하지 않았다(`#56` 설계 문서에도 동일하게 기록됨).
- `SearchQueryCommandService.markFailed()`/`RagResponseCommandService.createFailed()`의 `REQUIRES_NEW` 트랜잭션 경계는 여전히 Mockito 단위 테스트로만 검증되고, Spring 통합 테스트는 없다(`#56`, `#73` 문서에 동일하게 기록된 기존 갭).

### 다음 단계
RAG 블록(F-RAG-01~05) 전체 구현이 이걸로 완료된다. 이제 실제 문서를 업로드해 인덱싱까지 마친 뒤 Swagger에서 `POST /search`를 직접 호출해, `results` + `answer` + `citations`가 한 응답에 정상적으로 담기는지 e2e로 확인하는 절차가 남아있다.
