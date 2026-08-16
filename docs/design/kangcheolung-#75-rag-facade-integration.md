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

### 10. `domain/rag/service/RagFacade.java` — 이번 이슈의 핵심 조율자 (`#210`에서 후보 수 상한/citations 숨김/extractive fallback 추가)

**현재 코드**:
```java
@Transactional
@Service
@RequiredArgsConstructor
@Slf4j
public class RagFacade {

    private static final String LLM_FALLBACK_PREFIX = "AI 답변 생성이 지연되고 있습니다. "
        + "가장 관련도 높은 문서에서 다음 내용을 찾았습니다:\n\n";

    // fallback 문구에 원문을 통째로 붙이면 답변이 지나치게 길어져, 미리보기 수준으로만 잘라 보여준다.
    private static final int FALLBACK_EXCERPT_MAX_CODE_POINTS = 300;

    // topK는 호출자가 1~20까지 자유롭게 요청할 수 있어(SearchRequest), 후보 수를 그대로 프롬프트에
    // 다 넣으면 prefill 시간이 예측 불가능해져 read-timeout(25s)을 넘기는 경우가 생긴다.
    // 화면에 보여줄 인용 문서 수(topK)와 별개로, LLM이 실제로 읽는 후보 수는 이 값으로 고정한다.
    private static final int MAX_PROMPT_CANDIDATES = 3;

    // PromptBuilder가 LLM에게 무관한 문서일 때 이 문구로만 답하도록 지시한다 — 검색은 됐지만(candidates
    // 존재) LLM이 무관하다고 판단한 경우, 화면에 근거 문서를 같이 보여주면 안내 문구와 모순돼 보인다.
    private static final String NO_RELEVANT_DOC_PHRASE = "관련 문서를 찾지 못했습니다";

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

        // 검색 후보가 있으면 프롬프트 조립 후 LLM 호출 (LLM 입력은 상위 MAX_PROMPT_CANDIDATES개로 제한)
        List<VectorSearchCandidate> promptCandidates = candidates.size() > MAX_PROMPT_CANDIDATES
            ? candidates.subList(0, MAX_PROMPT_CANDIDATES)
            : candidates;
        String prompt = promptBuilder.build(queryText, promptCandidates);
        OllamaGenerateResult result;
        try {
            result = ollamaClient.generate(prompt);
        } catch (DocGridException e) {
            ragResponseCommandService.createFailed(queryRef, prompt, e.getMessage());
            // LLM 장애가 권한 검증을 통과한 벡터 검색 결과까지 숨기지 않도록, 최상위 후보 원문을
            // 그대로 인용해 최소한의 답을 제공한다(extractive fallback).
            log.warn("[RAG] fallback queryId={} errorCode={}", queryId, e.getErrorCode().getCode());
            return RagAnswer.of(buildExtractiveFallbackAnswer(candidates), candidates);
        }

        // LLM 이후의 영속화 실패는 검색 저하 응답으로 숨기지 않고 Transaction 오류로 전달한다.
        RagResponse ragResponse = ragResponseCommandService.createSuccess(queryRef, prompt, result);
        responseCitationCommandService.saveAll(ragResponse, candidates, searchResults);
        log.info("[RAG] done queryId={} responseId={} latencyMs={}", queryId, ragResponse.getId(), result.latencyMs());

        // LLM이 무관하다고 판단해 안내 문구로만 답했으면, 후보 문서를 근거처럼 같이 보여주지 않는다.
        if (result.answerText() != null && result.answerText().contains(NO_RELEVANT_DOC_PHRASE)) {
            return RagAnswer.of(result.answerText(), List.of());
        }
        return RagAnswer.of(result.answerText(), candidates);
    }

    private String buildExtractiveFallbackAnswer(List<VectorSearchCandidate> candidates) {
        VectorSearchCandidate top = candidates.get(0);
        String pageSuffix = top.pageNo() != null ? " " + top.pageNo() + "페이지" : "";
        String excerpt = truncate(top.chunkText(), FALLBACK_EXCERPT_MAX_CODE_POINTS);
        return "%s\"%s\" (%s%s)".formatted(LLM_FALLBACK_PREFIX, excerpt, top.documentTitle(), pageSuffix);
    }

    private String truncate(String text, int maxCodePoints) {
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxCodePoints - 1);
        return text.substring(0, endIndex) + "…";
    }
}
```

**`entityManager.getReference(SearchQuery.class, queryId)`를 쓰는 이유**: `RagFacade`는 `SearchFacade`와 다른 트랜잭션에서 실행된다. `SearchFacade`가 갖고 있던 진짜 `SearchQuery` 엔티티 객체를 그대로 넘겨받으면 detached 상태 문제가 생길 수 있어서, `queryId`(Long)만 받아 이 트랜잭션 안에서 새로 프록시를 만든다. `SearchResultCommandService`가 `DocumentChunk`/`Embedding` FK에 이미 쓰고 있는 것과 동일한 기법이다.

**`@Transactional`을 클래스에 붙인 이유**: `createSuccess()`(또는 `createNoContext()`)와 `saveAll()`(citation 저장)이 하나의 원자적 단위로 묶이길 원했다 — 답변은 저장됐는데 출처 저장이 실패해서 어중간하게 남는 상황을 피하기 위함이다. `SearchFacade`와는 별개의 트랜잭션이므로(Context 문단 참고), 검색 DB 작업과 섞이지 않는다. Ollama HTTP 호출이 이 트랜잭션 안에 포함되는 것 자체는 `SearchFacade`가 임베딩 HTTP 호출을 트랜잭션에 포함하는 것과 동일한 기존 트레이드오프를 그대로 따른다(MVP 단계 단순성 우선, 두 설계 문서 모두에 명시된 남은 이슈).

**실패 시 처리 — extractive fallback (200 응답)**: 최초 구현은 `catch (DocGridException e)`에서 실패 기록만 남기고 예외를 그대로 재전파해 503으로 응답했다. 이후 어느 시점(`#210` 범위 밖, 정확한 이슈 미상)에 "정적 안내 문구 + candidates를 citations로" 반환하는 방식(200 응답)으로 이미 바뀌어 있었고, `#210`에서 그 정적 문구를 **최상위 검색 후보 원문을 최대 300자까지 그대로 인용**하는 방식으로 다시 개선했다 — 실패해도 사용자가 빈손으로 끝나지 않도록. 이때도 이미 커밋된 검색 결과(`search_results`)는 별도 트랜잭션(`SearchFacade`)에서 저장된 것이라 영향받지 않고 그대로 남는다.

**LLM 입력 후보 수 상한(`MAX_PROMPT_CANDIDATES = 3`, `#210`)**: `topK`는 호출자가 1~20까지 정할 수 있는데(`SearchRequest`), 검색된 후보를 그대로 프롬프트에 다 넣다 보니 후보 개수에 따라 prefill 시간이 들쭉날쭉해 read-timeout을 넘기는 일이 잦았다. 화면에 보여줄 인용 문서 수(`topK`)와 별개로, `PromptBuilder.build()`에 넘기는 후보만 상위 3개로 고정했다 — citations/fallback에는 여전히 전체 `candidates`를 쓴다.

**LLM이 "무관하다"고 판단하면 citations를 비운다 (`#210`)**: `PromptBuilder`가 무관한 문서일 때 `"관련 문서를 찾지 못했습니다"`로만 답하도록 지시하는데(`#65` 문서), 검색 자체는 성공해서 `candidates`가 비어있지 않은 상태라 기존 로직대로면 이 후보들이 citations로 그대로 노출됐다. "관련 문서 없음" 메시지와 "근거 문서 목록"이 동시에 뜨는 게 모순돼 보여서, 답변이 이 문구를 포함하면 citations를 빈 배열로 반환하도록 분기를 추가했다(DB에는 그대로 저장 — 감사/분석용). **프론트도 같이 고쳐야 했다** — `frontend/app/lib/search-sources.ts`의 `groupSearchSources`가 "citations 비면 원본 검색 `results`로 대체해서 보여주는" fallback을 갖고 있어서, 백엔드만 고치면 이 fallback이 그대로 무력화시켰다. 이 fallback을 제거해 citations만 근거로 렌더링하게 바꿨다.

**(추가 수정, `#210`) 문구 위치에 따라 처리를 분기**: 위 로직을 처음엔 `answerText.contains(NO_RELEVANT_DOC_PHRASE)` 한 방으로 판정했는데, QA 중 7B 모델이 **정상 답변을 다 끝내놓고 지시문을 메아리처럼 답변 끝에 덧붙이는** 패턴이 반복 관찰됐다(예: 디렉토리 요약을 멀쩡히 마친 뒤 "관련 문서를 찾지 못했습니다. 질문 주제와 관련된 문서가 없습니다."를 스스로 추가). `contains()`로는 이런 경우도 전부 "무관"으로 오판해 멀쩡한 답변의 근거 문서까지 숨겨버렸다. 문구의 **위치**로 분기하도록 고쳤다:

```java
String answerText = result.answerText();
int phraseIndex = answerText != null ? answerText.indexOf(NO_RELEVANT_DOC_PHRASE) : -1;
if (phraseIndex >= 0) {
    if (answerText.strip().startsWith(NO_RELEVANT_DOC_PHRASE)) {
        return RagAnswer.of(answerText, List.of());
    }
    log.warn("[RAG] 정상 답변에 무관 안내 문구 혼입, 해당 지점부터 제거: queryId={} phraseIndex={}",
        queryId, phraseIndex);
    answerText = answerText.substring(0, phraseIndex).strip();
}
return RagAnswer.of(answerText, candidates);
```
문구가 답변 맨 앞(사실상 전부)이면 기존대로 진짜 무관 처리(citations 비움). 문구가 중간·끝에 섞여 있으면 그 지점부터 잘라내고 **citations는 유지**한다 — 화면에는 잘린 정상 답변 + 정상 근거 문서가 나간다. `log.warn`으로 발생 빈도를 추적한다.

전체 배경과 실측 데이터는 `docs/design/kangcheolung-#210-ollama-rag-timeout-fix.md` 참고.

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

기존 검색 블록 테스트(`SearchFacadeTest`, `SearchResultCommandServiceTest`)와 RAG 블록 테스트(`RagResponseCommandServiceTest`, `ResponseCitationCommandServiceTest`)를 이번 이슈의 시그니처 변경에 맞춰 함께 수정했고, 신규 `RagFacadeTest`(NO_CONTEXT/정상/실패 3케이스)를 추가했다. 전체 테스트 스위트가 회귀 없이 통과했다. (`#210`에서 "LLM 무관 판단 시 citations 비움", "후보 3개 초과 시 프롬프트엔 상위 3개만", "무관 문구가 답변 중간에 섞이면 그 지점부터 제거하고 citations는 유지" 3케이스가 추가되어 현재 6케이스다.)

실제 문서 업로드/인덱싱 후 `POST /search`를 Swagger로 호출하는 e2e 확인은 이후 QA에서 완료됐다(아래 "다음 단계" 참고 — 그 과정에서 발견된 버그와 수정 내역은 `#210` 문서에 정리). 이 문서에는 자동화 테스트 결과만 기록한다.

---

## 에러 케이스 정리

| 상황 | 처리 |
|---|---|
| 검색 자체 실패(임베딩 서버 장애, 사용자/컬렉션 없음 등) | 기존 `SearchFacade`의 에러 처리 그대로(변경 없음) — `RagFacade`는 호출되지도 않음 |
| 접근 가능 문서 0건 / live check로 전부 탈락 | `SearchOutcome.candidates()`가 빈 리스트 → `RagFacade`가 `createNoContext()`로 처리, 200 정상 응답 + 고정 answer 문구 |
| Ollama 호출 실패(타임아웃/연결거부) | `createFailed()`로 FAILED 기록 후 200 + extractive fallback(최상위 후보 원문 최대 300자 인용) answer + `citations`(candidates 전체). 검색 결과(`search_results`)는 이미 별도 트랜잭션에서 커밋되어 그대로 유지됨. (최초 구현은 예외 재전파 → 503이었으나 이후 200 응답으로 바뀜, 위 "10. `RagFacade.java`" 절 참고) |
| LLM이 무관하다고 판단 (`#210`) | 200 + answer(`"관련 문서를 찾지 못했습니다"`) + **citations는 빈 배열** (DB에는 그대로 저장) |
| 정상 흐름 | 200 + `results`(검색 후보 전체) + `answer`(LLM 답변) + `citations`(실제 인용된 출처) |

---

## 코드리뷰 반영 (CodeRabbit)

PR에 자동 코드리뷰 코멘트 5건이 달렸고, 각각 다음과 같이 처리했다.

| # | 코멘트 요지 | 처리 | 근거 |
|---|---|---|---|
| 1 | `RagResponse.llmProvider`/`llmModelName` 필드 주석에 `(Ollama)`, `(qwen2.5:3b)`처럼 특정 값을 박아뒀다 | **반영함** | `llmModelName`은 하드코딩이 아니라 `OllamaGenerateResult.model()`에서 매 호출마다 동적으로 채워지는 값이다(모델을 3b→7b로 바꿔도 코드 수정 없이 대응하기 위한 설계, `#67` 문서 참고). 주석에 특정 모델명을 박아두면 그 설계 의도와 모순되고, NO_CONTEXT 응답에서는 두 필드 다 비어있기도 해서 필드 역할만 남기는 쪽으로 단순화했다 |
| 2 | `RagFacade` Javadoc의 `(F-RAG-05)` 표기를 "내부 PR 순번 라벨"이라며 제거 요청 | **반영 안 함** | 이건 PR 순번이 아니라 RAG 명세서의 기능 코드다. `SearchFacade`(F-SEARCH-05/06/07), `RagResponseCommandService`(F-RAG-03), `ResponseCitationCommandService`(F-RAG-04) 등 이 코드베이스 전체가 일관되게 이 표기를 쓰고 있어서, 여기만 빼면 형제 클래스들과 일관성이 깨진다 |
| 3 | 신규 `CitationResponse` record에 class-level Javadoc이 없다 | **반영 안 함** | 구조가 가장 비슷한 `SearchResultItem`(rank 기반 응답 DTO + `of()` 팩토리)도 class-level 주석이 없어, 기존 관례와의 일관성을 우선했다 |
| 4 | `ResponseCitationCommandService.saveAll()`이 `SearchFacade`의(이미 detached된) `SearchResult` 엔티티를 그대로 `.searchResult(...)`에 대입하고 있어 위험하다 | **반영함** | 실제로 안전하지 않은 패턴이었다. 아래 별도 문단에서 상세 설명 |
| 5 | `SearchFacadeTest`의 정상 흐름 테스트가 `saveAll()`을 빈 리스트로 stub해둬서, `SearchOutcome.savedResults()` 전달 여부를 실질적으로 검증하지 못하고 있다 | **반영함** | `saveAll()`이 mock `SearchResult` 하나를 반환하도록 바꾸고, `outcome.savedResults()`가 그 값을 그대로 담고 있는지 검증을 추가했다 |

### 4번 상세 — `entityManager.getReference()`를 쓰는 두 가지 서로 다른 이유

이 코드베이스에는 `entityManager.getReference(Class, id)` 패턴이 여러 곳에 나오는데, 사실 이유가 두 가지로 갈린다.

| 위치 | 이유 |
|---|---|
| `SearchResultCommandService.saveAll()`의 `chunk`/`embedding`, `ResponseCitationCommandService.saveAll()`의 `chunk` (기존부터 있던 코드) | **성능** — 존재가 이미 확실한 엔티티(검색으로 찾아온 chunk 등)의 FK만 연결하면 되는데, `findById()`를 쓰면 불필요한 SELECT가 추가로 나간다. `getReference()`는 실제 쿼리 없이 ID값만 가진 프록시를 만들어 FK 컬럼에 연결한다 |
| `RagFacade.generate()`의 `SearchQuery`, `ResponseCitationCommandService.saveAll()`의 `searchResult` (이번에 수정) | **안전성** — `SearchFacade`(다른 트랜잭션)에서 넘어온 엔티티는 이미 detached 상태다. 그 객체를 새 엔티티의 FK로 그대로 재사용하는 대신, `id`만 꺼내서(`getId()`) 지금 이 트랜잭션 안에서 `getReference()`로 새 프록시를 만든다 |

`searchResult` 필드는 원래(수정 전) `searchResults.get(i)`를 그대로 대입하고 있었는데, `cascade` 설정이 없어서 당장 예외가 나지는 않지만 트랜잭션 경계를 넘어온 엔티티를 그대로 재사용하는 건 이 코드베이스의 다른 모든 FK 연결 지점(`chunk`, `embedding`, `SearchQuery`)과 방식이 달라 일관성이 깨지고, 더 안전한 방법이 이미 옆 줄(`chunk`)에 있는데 안 쓴 셈이었다. `entityManager.getReference(SearchResult.class, searchResults.get(i).getId())`로 바꿔서 나머지 FK 연결과 동일한 방식으로 통일했다.

```java
// 수정 전 — detached 엔티티를 그대로 FK에 대입
.searchResult(searchResults.get(i))

// 수정 후 — id만 꺼내 이번 트랜잭션의 프록시로 새로 참조
.searchResult(entityManager.getReference(SearchResult.class, searchResults.get(i).getId()))
```

---

## 설계 결정 요약

**`SearchFacade`/`RagFacade` 트랜잭션 분리**: `SearchController`가 두 Facade를 순차 호출하는 구조 자체로 트랜잭션이 물리적으로 나뉜다. LLM HTTP 호출(최대 30초)이 검색 DB 작업과 같은 커넥션을 오래 물고 있지 않도록 하기 위함.

**`SearchOutcome`/`RagAnswer` — API로 노출되지 않는 내부 전달용 레코드 2개 도입**: `SearchFacade`→`Controller`, `RagFacade`→`Controller` 각각의 결과를 담는 그릇을 분리해서, 최종 API 응답(`SearchResponse`)과 내부 처리에 필요한 데이터(원본 후보, 저장된 엔티티)를 섞지 않았다.

**`CitationResponse`를 search 도메인에 배치해 순환 참조 방지**: RAG가 이미 Search에 의존하는 기존 방향을 유지하기 위한 의도적 선택.

**NO_CONTEXT와 FAILED를 대칭적으로 설계**: 둘 다 `RagResponseCommandService`에 고정 문구 상수 + 전용 생성 메서드로 존재한다. 차이는 `status`(SUCCESS vs FAILED)와 트랜잭션 전파(`createNoContext`는 일반 `REQUIRED`, `createFailed`는 `REQUIRES_NEW`) 정도다.

**citations 응답은 DB 재조회 없이 메모리의 `candidates`로부터 재구성**: `ResponseCitationCommandService`가 저장한 것과 `RagAnswer.of()`가 만드는 것은 별개의 객체 생성이지만, 소스(`candidates`)와 라벨 규칙(`"[" + order + "]"`)이 동일해 항상 일치한다.

**(`#210` 추가) 화면 표시용 인용 수(topK)와 LLM 입력 후보 수를 분리**: `topK`가 호출자가 자유롭게 정할 수 있는 값이라 그대로 LLM에 넘기면 응답 시간이 예측 불가능해졌다. `citations`/`RagAnswer`는 여전히 전체 `candidates`를 쓰고, `promptBuilder.build()`에 넘기는 것만 `MAX_PROMPT_CANDIDATES`(3)로 별도 제한해 "화면에 보여줄 근거 수"와 "LLM이 실제로 읽는 문맥 크기"라는 서로 다른 관심사를 분리했다.

---

## 남은 이슈 / TODO

### 코드
- `SearchFacade`(임베딩 HTTP 호출 포함)와 `RagFacade`(Ollama HTTP 호출 포함) 둘 다 "트랜잭션 안에 HTTP 호출 포함" 트레이드오프를 갖고 있다 — 두 곳 다 아직 정식으로 트랜잭션 분리 리팩터링은 하지 않았다(`#56` 설계 문서에도 동일하게 기록됨).
- `SearchQueryCommandService.markFailed()`/`RagResponseCommandService.createFailed()`의 `REQUIRES_NEW` 트랜잭션 경계는 여전히 Mockito 단위 테스트로만 검증되고, Spring 통합 테스트는 없다(`#56`, `#73` 문서에 동일하게 기록된 기존 갭).

### 다음 단계
~~RAG 블록(F-RAG-01~05) 전체 구현이 이걸로 완료된다. 이제 실제 문서를 업로드해 인덱싱까지 마친 뒤 Swagger에서 `POST /search`를 직접 호출해, `results` + `answer` + `citations`가 한 응답에 정상적으로 담기는지 e2e로 확인하는 절차가 남아있다.~~ → 완료됨: 이 e2e 확인이 QA 과정에서 실제로 진행됐고, 그 과정에서 발견된 타임아웃/언어 혼용/컷오프 등 다수의 버그와 수정 내역은 `docs/design/kangcheolung-#210-ollama-rag-timeout-fix.md`에 정리했다. `PromptBuilder` 관련 변경은 `#65`, `OllamaClient` 관련 변경은 `#67` 문서에도 각각 반영했다.

`#210`에서 새로 남은 미해결 이슈(Ollama `OLLAMA_KV_CACHE_TYPE` 글자 깨짐 등)는 `#67` 문서의 "남은 이슈 / TODO" 및 `#210` 문서 5단계 참고.
