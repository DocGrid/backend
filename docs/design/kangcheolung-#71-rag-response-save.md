# #71 rag_responses 저장 — RagResponseCommandService 구현 (F-RAG-03)

closes #71

---

## 배경

Issue 2(#67)에서 `OllamaClient.generate()`가 Ollama를 호출해 답변을 생성하는 것까지 만들었다. 하지만 그 결과는 메서드 호출이 끝나면 메모리에서 사라진다 — DB에 아무 흔적도 남지 않는다. Issue 3은 이 결과(성공이든 실패든)를 `rag_responses` 테이블에 실제로 기록하는 코드를 만든다. RAG 명세가 강조하는 "왜 이런 답변이 나왔는지 추적 가능해야 한다"는 원칙이 실제로 구현되는 지점이다.

마이그레이션(`V23__create_rag_responses.sql`)과 엔티티(`RagResponse.java`)는 이슈 착수 전부터 이미 존재했다. 이번 이슈는 `RagResponseRepository` + `RagResponseCommandService`만 새로 만들고, 그 과정에서 Issue 2 산출물(`OllamaGenerateResponse`/`OllamaGenerateResult`/`OllamaClient`)에 필드 하나를 추가하는 소폭 수정이 같이 들어갔다.

| Issue | 범위 | 상태 |
|---|---|---|
| 1 | Ollama 로컬 세팅 + PromptBuilder (F-RAG-01) | 완료 |
| 2 | OllamaClient 연동 (F-RAG-02) | 완료 |
| **3 (이 문서)** | rag_responses 저장 (F-RAG-03) | 완료 |
| 4 | response_citations 저장 (F-RAG-04) | 예정 |
| 5 | 최종 answer + citations 응답 조합 (F-RAG-05) | 예정 |

**이번 이슈에서 하지 않는 것**: `response_citations` 저장(Issue 4), `SearchFacade`/`SearchController`와의 연결(Issue 5), 새 API 엔드포인트/컨트롤러(RAG 블록은 애초에 자기만의 엔드포인트를 안 만든다 — 명세 6장, 기존 `POST /search`에 결국 얹히는 구조). `RagResponseCommandService`는 Issue 1의 `PromptBuilder`, Issue 2의 `OllamaClient`처럼 이번 이슈에서는 단위 테스트로만 검증되고 아직 다른 코드와 연결되지 않은 독립 컴포넌트다.

---

## 전체 흐름

```text
(아직 어디에도 연결되지 않음 — Issue 5에서 연결 예정)
OllamaClient.generate(prompt)  ← Issue 2
        │
        ├─ 성공 → OllamaGenerateResult(model, answerText, inputTokenCount, outputTokenCount, latencyMs)
        │           │
        │           ▼
        │       RagResponseCommandService.createSuccess(query, promptText, result)  ← 이 문서
        │           │
        │           ▼
        │       rag_responses INSERT (status=SUCCESS)
        │
        └─ 실패 → DocGridException(RAG_SERVICE_UNAVAILABLE)
                    │
                    ▼
                RagResponseCommandService.createFailed(query, promptText, errorMessage)  ← 이 문서
                    │
                    ▼
                rag_responses INSERT (status=FAILED, REQUIRES_NEW로 별도 트랜잭션 커밋)
```

---

## 신규/변경 파일

### 1. `domain/rag/dto/response/OllamaGenerateResponse.java` — `model` 필드 추가 (변경)

```java
public record OllamaGenerateResponse(
    String model,
    String response,
    boolean done,
    @JsonProperty("prompt_eval_count") Integer promptEvalCount,
    @JsonProperty("eval_count") Integer evalCount
) {
}
```

**왜 고쳤나**: `rag_responses.llm_model_name` 컬럼을 채우려면 "실제로 어떤 모델이 답변을 생성했는지"가 필요한데, 지금까지 `OllamaGenerateResponse`는 그 정보를 받지 않고 있었다. Ollama 응답 JSON에 이미 `"model": "qwen2.5:3b"`처럼 모델명이 포함되어 있음을 Issue 2에서 curl로 확인했으므로(`docs/design/kangcheolung-#67-ollama-client.md` 참고), 그 필드를 그대로 받도록 추가했다.

### 2. `domain/rag/dto/OllamaGenerateResult.java` — `model` 필드 추가 (변경)

```java
public record OllamaGenerateResult(
    String model,                // 실제 응답을 생성한 모델명 (Ollama 응답의 model 필드)
    String answerText,
    Integer inputTokenCount,
    Integer outputTokenCount,
    int latencyMs
) {
}
```

**왜 고쳤나 (대안 비교)**: `llm_model_name`을 채우는 방법은 두 가지가 있었다.

| | 방법 A (채택) | 방법 B (기각) |
|---|---|---|
| 값의 출처 | Ollama 응답 자체의 `model` 필드 | `RagFacade`가 `@Value("${ollama.model}")`을 별도로 다시 주입받아 전달 |
| 문제점 없음 | "어떤 모델을 쓸지"에 대한 지식이 한 곳(`OllamaClient`)에만 존재 | 같은 지식이 `OllamaClient`와 `RagFacade` 두 곳에 중복 — 나중에 설정이 바뀌었는데 한쪽만 반영되는 불일치 위험 |

Ollama가 실제로 응답한 모델명을 그대로 쓰는 게 항상 정확하므로 A를 선택했다. `OllamaGenerateResponse`(Ollama 응답에 종속된 그릇)에 있던 값을 `OllamaGenerateResult`(우리 서비스가 실제로 쓰는 그릇)로 그대로 옮겨 담았다.

### 3. `domain/rag/service/OllamaClient.java` — 결과 조립부 1줄 수정 (변경)

```java
// 이전
return new OllamaGenerateResult(
    response.response(), response.promptEvalCount(), response.evalCount(), latencyMs
);

// 이후
return new OllamaGenerateResult(
    response.model(), response.response(), response.promptEvalCount(), response.evalCount(), latencyMs
);
```
1, 2번에서 필드를 추가했으니 실제로 값을 옮겨 담는 코드가 필요했다.

### 4. `src/test/java/.../rag/service/OllamaClientTest.java` — 기존 테스트 보정 (변경)

`OllamaGenerateResponse` 생성자 인자가 하나 늘었으므로, 이를 생성하던 기존 테스트 2곳(`generate_success`, `generate_nullAnswerText_throwsException`)에 `model` 값(`"qwen2.5:3b"`)을 채워 넣어 컴파일을 맞췄다. 겸사겸사 `generate_success`에 `result.model()` 검증도 추가했다.

### 5. `domain/rag/repository/RagResponseRepository.java` (신규)

```java
public interface RagResponseRepository extends JpaRepository<RagResponse, Long> {
}
```
`SearchQueryRepository`와 동일하게, 지금은 커스텀 쿼리가 필요 없어 빈 인터페이스로 둔다. Spring Data JPA가 `save()` 등 기본 메서드를 자동 구현한다.

### 6. `domain/rag/service/command/RagResponseCommandService.java` (신규)

```java
@Transactional
@Service
@RequiredArgsConstructor
public class RagResponseCommandService {

    private static final String LLM_PROVIDER = "Ollama";
    private static final String FAILED_ANSWER_TEXT = "답변 생성에 실패했습니다.";

    private final RagResponseRepository ragResponseRepository;

    public RagResponse createSuccess(SearchQuery query, String promptText, OllamaGenerateResult result) {
        RagResponse ragResponse = RagResponse.builder()
            .query(query)
            .answerText(result.answerText())
            .llmProvider(LLM_PROVIDER)
            .llmModelName(result.model())
            .promptText(promptText)
            .inputTokenCount(result.inputTokenCount())
            .outputTokenCount(result.outputTokenCount())
            .latencyMs(result.latencyMs())
            .status(ResultStatus.SUCCESS)
            .build();
        return ragResponseRepository.save(ragResponse);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RagResponse createFailed(SearchQuery query, String promptText, String errorMessage) {
        RagResponse ragResponse = RagResponse.builder()
            .query(query)
            .answerText(FAILED_ANSWER_TEXT)
            .llmProvider(LLM_PROVIDER)
            .promptText(promptText)
            .status(ResultStatus.FAILED)
            .errorMessage(errorMessage)
            .build();
        return ragResponseRepository.save(ragResponse);
    }
}
```

**한 줄 요약**: `OllamaClient` 호출 결과(성공/실패)를 받아 `rag_responses`에 저장하는, 이번 이슈의 핵심 클래스.

**왜 PROCESSING 중간 상태 없이 1회성 저장인가**: `SearchQueryCommandService`는 `createProcessing()` → (나중에) `markSuccess()`/`markFailed()`로 2단계였다 — 검색은 임베딩·권한필터·벡터검색·live check 등 여러 단계를 거치는 긴 과정이라 중간 상태(PROCESSING)가 의미 있었다. 반면 `OllamaClient.generate()`는 한 번 호출해서 성공하거나 예외를 던지거나 둘 중 하나로 바로 끝나는 단일 작업이다. 결과가 이미 나온 시점에 이 서비스가 호출되므로, 중간 상태를 저장할 이유가 없다 — `createSuccess()`/`createFailed()` 두 메서드가 각각 최종 상태로 한 번에 저장한다.

**왜 FAILED에도 `answerText`가 채워지는가 (`FAILED_ANSWER_TEXT`)**: `V23` 마이그레이션에 `answer_text TEXT NOT NULL` 제약이 이미 걸려 있다(마이그레이션은 한 번 적용되면 새 파일 추가로만 대응 가능 — `db-migration.md`). 실패한 경우에도 이 컬럼에 값이 있어야 하므로, Issue 1에서 이미 썼던 "NO_CONTEXT일 때 고정 응답 문구" 패턴과 동일하게 `"답변 생성에 실패했습니다."`라는 고정 문자열을 저장한다. 실제 실패 사유는 별도 컬럼인 `error_message`에 담는다.

**왜 `createFailed()`에만 `REQUIRES_NEW`인가**: Issue 5에서 `RagFacade`가 `OllamaClient.generate()`를 호출했다가 실패하면, `createFailed()`로 실패 기록을 남긴 뒤 예외를 다시 던져 컨트롤러가 503을 응답하게 될 것이다. 이때 예외가 위로 전파되면서 감싸고 있던 트랜잭션이 롤백될 수 있는데, 그러면 방금 저장한 실패 기록까지 함께 사라진다. `@Transactional(propagation = Propagation.REQUIRES_NEW)`는 "이 메서드는 항상 별도의 새 트랜잭션에서 실행한다"는 의미라, 바깥 트랜잭션이 롤백되어도 이 실패 기록만은 독립적으로 커밋되어 남는다. `SearchQueryCommandService.markFailed()`가 이미 쓰고 있는 것과 동일한 패턴이다.

**왜 `llmProvider`를 상수로 하드코딩했나**: 명세 11장("1단계는 qwen2.5:3b 단일 모델만 사용, 여러 LLM 비교는 2단계")에 따라 지금은 "Ollama"라는 값이 바뀔 일이 없다. 프로바이더 추상화(enum, 별도 설정값 등)를 지금 만드는 건 아직 필요 없는 유연성이다.

### 7. `src/test/java/.../rag/service/command/RagResponseCommandServiceTest.java` (신규)

`SearchQueryCommandServiceTest`와 동일한 패턴 — `@InjectMocks`/`@Mock` + `ArgumentCaptor`로 저장 직전의 엔티티를 가로채 필드를 검증한다.

| 테스트 | 검증 내용 |
|---|---|
| `createSuccess_savesWithSuccessStatus` | `status=SUCCESS`, `answerText`/`llmModelName`/`inputTokenCount`/`outputTokenCount`/`latencyMs`가 `OllamaGenerateResult`에서 그대로 담기는지, `llmProvider="Ollama"`인지 |
| `createFailed_savesWithFailedStatus` | `status=FAILED`, `answerText`가 고정 문구(`"답변 생성에 실패했습니다."`)인지, `errorMessage`가 그대로 담기는지, `llmProvider="Ollama"`인지 |

`query` 파라미터는 기존 `SearchQueryFixture.createProcessing()`을 재사용했다 — RAG 전용 Fixture는 아직 재사용처가 없어 새로 만들지 않았다.

---

## 로컬 검증 (실제 수행 기록)

```bash
$ ./gradlew test --tests "*RagResponseCommandServiceTest*" --tests "*OllamaClientTest*"
BUILD SUCCESSFUL

$ ./gradlew build -x test
BUILD SUCCESSFUL

$ ./gradlew test   # 전체 테스트 스위트 — 검색 블록 등 기존 테스트에 영향 없는지 확인
BUILD SUCCESSFUL
```
`OllamaGenerateResponse`/`OllamaGenerateResult`처럼 다른 곳에서도 참조될 수 있는 공용 DTO를 수정했기 때문에, 이번 이슈만이 아니라 전체 테스트 스위트를 돌려 회귀가 없는지 확인했다.

---

## 에러 케이스 정리

`RagResponseCommandService`는 예외를 던지지 않는다 — 저장 성공/실패라는 결과를 상태값(`status`)으로 표현하는 컴포넌트이지, 저장 그 자체가 실패하는 경우(DB 장애 등)는 이 이슈의 관심사가 아니다.

| 상황 | 처리 |
|---|---|
| Ollama 호출 성공 | `createSuccess()` — `status=SUCCESS`, 답변/토큰수/latency/모델명 모두 기록 |
| Ollama 호출 실패 (Issue 2의 `RAG_SERVICE_UNAVAILABLE`) | `createFailed()` — `status=FAILED`, 고정 답변 문구 + 실제 사유(`errorMessage`) 기록, `REQUIRES_NEW`로 상위 트랜잭션 롤백과 무관하게 저장 |

---

## 설계 결정 요약

**PROCESSING 없는 1회성 저장**: `OllamaClient.generate()`가 이미 결과를 확정한 뒤에 이 서비스가 호출되므로, 검색 블록처럼 중간 상태를 둘 이유가 없다.

**`OllamaGenerateResult`에 `model` 필드를 추가해 단일 진실 공급원(single source of truth) 유지**: "어떤 모델을 썼는지"를 여러 곳에서 각자 알고 있게 만들지 않고, Ollama의 실제 응답에서 한 번만 가져와 그대로 전달되게 했다.

**`answer_text NOT NULL` 제약을 고정 문구로 우회**: 마이그레이션 재수정 불가 규칙 때문에, 실패 케이스도 이 컬럼에 값을 채워야 했다. Issue 1의 NO_CONTEXT 고정 응답과 동일한 패턴을 재사용해 일관성을 유지했다.

**FAILED 기록의 트랜잭션 격리(`REQUIRES_NEW`)**: 검색 블록에서 이미 검증된 패턴(`SearchQueryCommandService.markFailed()`)을 그대로 재사용해, "장애가 발생했다"는 사실 자체가 상위 트랜잭션 롤백에 휩쓸려 사라지지 않도록 했다.

**컨트롤러/엔드포인트 없음**: RAG 블록은 명세상 자기만의 API를 갖지 않는다(6장). `RagResponseCommandService`도 다른 RAG 컴포넌트들처럼 아직 아무 곳에도 연결되지 않은 독립 부품이며, Issue 5에서 `RagFacade`를 통해 기존 `POST /search` 흐름에 편입된다.

---

## 남은 이슈 / TODO

### 코드
- `RagResponseCommandService`는 아직 어디에서도 호출되지 않는 독립 컴포넌트 — Issue 5에서 `RagFacade`가 실제로 연결한다.
- `createSuccess`/`createFailed`가 반환하는 `RagResponse`(특히 `getId()`)는 Issue 4에서 `response_citations.response_id`로 그대로 연결될 예정이다.

### 다음 단계
Issue 4 — `ResponseCitationRepository` + `CitationService`(가칭) 구현. Issue 1에서 `PromptBuilder`가 라벨을 매긴 것과 동일한 `List<VectorSearchCandidate>` 순서를 그대로 재사용해 `citation_order`/`citation_label`을 채우고, `chunk_id`로 연결한다. `search_result_id`는 `SearchResultCommandService.saveAll()`이 현재 저장된 엔티티를 반환하지 않아(`void`) 이번 이슈 단독으로는 채우기 어려운 상태 — Issue 5에서 두 결과(검색 결과, RAG 응답)를 모두 쥐고 있는 시점에 연결하는 방안을 검토해야 한다.
