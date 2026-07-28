# #73 response_citations 저장 — ResponseCitationCommandService 구현 (F-RAG-04)

closes #73

---

## 배경

Issue 3(#71)에서 답변 문장(`rag_responses`) 저장까지 만들었다. 하지만 "이 답변이 어떤 chunk를 근거로 썼는지"는 아직 어디에도 기록되지 않는다. Issue 4는 그 근거를 `response_citations`에 구조화해서 저장한다 — 이 테이블이 있어야 "출처 기반 검색"이라고 말할 수 있다는 게 RAG 명세의 핵심 전제다.

마이그레이션(`V24__create_response_citations.sql`)과 엔티티(`ResponseCitation.java`)는 이슈 착수 전부터 이미 존재했다. 이번 이슈는 `ResponseCitationRepository`와 저장 로직(Command 서비스)만 새로 만든다.

| Issue | 범위 | 상태 |
|---|---|---|
| 1 | Ollama 로컬 세팅 + PromptBuilder (F-RAG-01) | 완료 |
| 2 | OllamaClient 연동 (F-RAG-02) | 완료 |
| 3 | rag_responses 저장 (F-RAG-03) | 완료 |
| **4 (이 문서)** | response_citations 저장 (F-RAG-04) | 완료 |
| 5 | 최종 answer + citations 응답 조합 (F-RAG-05) | 예정 |

**이번 이슈에서 하지 않는 것**: `SearchFacade`/`SearchController`와의 연결(Issue 5), `search_result_id` 채우기(Issue 5 — 아래 "의도적으로 미룬 부분" 참고). `ResponseCitationCommandService`도 Issue 1~3의 다른 컴포넌트들처럼 아직 어디에도 연결되지 않은 독립 컴포넌트이며 단위 테스트로만 검증한다.

---

## 전체 흐름

```text
(아직 어디에도 연결되지 않음 — Issue 5에서 연결 예정)
PromptBuilder.build(queryText, candidates)  ← Issue 1이 이미 [1],[2]... 라벨을 매긴 그 candidates
        │
        ▼
OllamaClient.generate(prompt) → RagResponseCommandService.createSuccess(...)  ← Issue 2, 3
        │                              │
        │                              ▼
        │                         RagResponse (저장됨, id 확보)
        │
        └──────────────┬───────────────┘
                        ▼
        ResponseCitationCommandService.saveAll(ragResponse, candidates)  ← 이 문서
                        │
                        ▼
        response_citations INSERT × N (citation_order=1,2,3... citation_label="[1]","[2]"...)
                        │
                        └─ search_result_id는 이번 이슈에서 null (Issue 5에서 채움)
```

`candidates`(`List<VectorSearchCandidate>`)는 Issue 1의 `PromptBuilder`가 프롬프트 조립에 쓴 것과 **동일한 리스트, 동일한 순서**를 그대로 재사용한다 — 별도로 라벨을 다시 계산하지 않는다.

---

## 신규 파일

### 1. `domain/rag/repository/ResponseCitationRepository.java`

```java
public interface ResponseCitationRepository extends JpaRepository<ResponseCitation, Long> {
}
```
`RagResponseRepository`, `SearchQueryRepository`와 동일하게 커스텀 쿼리가 필요 없어 빈 인터페이스로 둔다.

### 2. `domain/rag/service/command/ResponseCitationCommandService.java`

```java
@Transactional
@Service
@RequiredArgsConstructor
public class ResponseCitationCommandService {

    private final ResponseCitationRepository responseCitationRepository;
    private final EntityManager entityManager;

    public void saveAll(RagResponse response, List<VectorSearchCandidate> candidates) {
        List<ResponseCitation> citations = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchCandidate c = candidates.get(i);
            citations.add(ResponseCitation.builder()
                .response(response)
                .chunk(entityManager.getReference(DocumentChunk.class, c.chunkId()))
                .citationOrder(i + 1)
                .citationLabel("[" + (i + 1) + "]")
                .quotedText(c.chunkText())
                .pageNo(c.pageNo())
                .relevanceScore(c.similarityScore())
                .build());
        }
        responseCitationRepository.saveAll(citations);
    }
}
```

**한 줄 요약**: 답변 하나(`RagResponse`)와 그 답변이 근거로 쓴 검색 후보 리스트를 받아, 후보마다 `ResponseCitation` 엔티티를 만들어 일괄 저장한다.

**왜 `SearchResultCommandService.saveAll()`을 그대로 본떴나**: "후보 리스트를 순회하며 엔티티를 만들고 `saveAll()`한다"는 문제 모양이 검색 블록에서 이미 검증된 것과 완전히 같다. 새로 고안하지 않고 그 구조(반복문 + `entityManager.getReference()` + `saveAll()`)를 그대로 재사용했다.

**`entityManager.getReference(DocumentChunk.class, c.chunkId())`를 쓰는 이유**: `chunk_id` FK를 연결할 때, 이 chunk가 실제로 존재하는지 확인하는 SELECT가 필요 없다(이미 검색에서 찾아온 chunk라 존재가 보장됨). `getReference()`는 실제 쿼리 없이 ID값만 가진 프록시를 만들어 FK 컬럼에 연결한다 — `findById()`를 썼다면 후보 N개마다 SELECT N번이 나갔을 것을 전부 생략한다. `SearchResultCommandService.saveAll()`이 `Embedding`/`DocumentChunk`에 대해 이미 쓰고 있는 것과 동일한 최적화다.

**`citationOrder`/`citationLabel`을 후보 리스트 인덱스로 정한 이유**: `candidates`는 이미 유사도 정렬 + live check를 거친 최종 순서이고, Issue 1의 `PromptBuilder`가 프롬프트에 `[1]`, `[2]`... 라벨을 매길 때 쓴 것과 **완전히 동일한 순서**다. 이 순서를 그대로 재사용하면 "프롬프트에서 [2]번으로 인용된 chunk"와 "DB에 citation_order=2로 저장된 chunk"가 항상 일치한다. 별도의 라벨 매핑 구조체 없이 리스트 인덱스(`i+1`)를 바로 쓴다.

**`citationLabel` 포맷을 `"[" + (i+1) + "]"`로 고정한 이유**: `PromptBuilder`의 라벨 포맷(`"[%d]"`)과 그대로 맞췄다. 두 곳에서 서로 다른 포맷을 쓰면 "프롬프트에 보이는 라벨"과 "DB에 저장된 라벨"이 시각적으로 어긋날 수 있어서, 문자열을 그대로 일치시켰다.

**`quotedText`에 chunk 원문 전체를 저장하는 이유**: 명세가 "핵심 문장(또는 전체)"로 열어뒀는데, 핵심 문장만 뽑으려면 문장 분리·중요도 판단 같은 별도 로직이 필요하다. 지금 단계에서 그 복잡도를 추가할 이유가 없어, 검색 블록의 `SearchResult.matchedText`가 이미 하는 것처럼 전체 텍스트를 그대로 저장한다. 부가적으로, 원본 `document_chunks`가 나중에 수정되더라도 "답변 당시 실제로 인용했던 문구"가 스냅샷처럼 남는다는 이점도 있다.

**`relevanceScore`에 `candidate.similarityScore()`를 그대로 넣는 이유**: 둘 다 이미 `BigDecimal`이라 변환 없이 대입 가능하고, "이 chunk가 왜 뽑혔는지"를 정량적으로 같이 남겨 나중에 답변 품질을 분석할 때 활용할 수 있다.

### 3. `src/test/java/.../rag/service/command/ResponseCitationCommandServiceTest.java`

`SearchResultCommandServiceTest`와 동일한 Mockito 패턴(`EntityManager.getReference` mock + `ArgumentCaptor<List<ResponseCitation>>`).

| 테스트 | 검증 내용 |
|---|---|
| `saveAll_savesWithOrderAndLabel` | 후보 2개 → `citationOrder` 1,2 / `citationLabel` "[1]","[2]" / `quotedText`/`pageNo`/`relevanceScore`가 후보 값 그대로 담기는지, `searchResult`가 `null`인지 |
| `saveAll_emptyCandidates_savesEmptyList` | 빈 리스트 입력 시 `saveAll`에 빈 리스트가 전달되는지 |

---

## 의도적으로 미룬 부분 — `search_result_id`

`ResponseCitation.searchResult`(`search_result_id` FK, nullable)는 "이 chunk가 검색 단계에서 정확히 몇 번째 `search_results` row였는지"를 연결하는 컬럼이다. 이번 이슈에서는 이 값을 **채우지 않는다** — `ResponseCitation.builder()`에서 `.searchResult(...)` 호출 자체를 생략한다.

**왜 못 채우나**: `SearchResultCommandService.saveAll()`(검색 블록, 기존 코드)이 지금 `void`를 반환한다. `SearchResult` 엔티티들을 DB에 저장은 하지만, 저장된 엔티티(및 그 `id`)를 호출한 쪽에 돌려주지 않는다. `ResponseCitationCommandService`는 `List<VectorSearchCandidate>`(순수 DTO, DB PK 아님)만 갖고 있어서, 실제 `search_results.id` 값을 이 시점에는 알 방법이 없다.

**왜 지금 억지로 채우지 않았나**: `search_result_id` 컬럼이 `nullable`이라 스키마상 비워둬도 문제가 없다. 이 값을 채우려면 검색 블록의 기존 코드(`SearchResultCommandService`)까지 함께 고쳐야 하는데, 그건 "검색 결과와 RAG 응답을 하나로 묶는" 작업이라 성격상 Issue 5(`RagFacade`가 두 결과를 모두 쥐는 시점)에서 하는 게 범위가 깔끔하다고 판단했다.

**Issue 5에서 할 일 (예고)**: `SearchResultCommandService.saveAll()`을 `List<SearchResult>`를 반환하도록 바꾸고, `RagFacade`가 그 결과와 `candidates`를 함께 갖고 있는 시점에 `ResponseCitationCommandService.saveAll()`(또는 그 오버로드)에 `search_result_id` 매핑까지 전달하도록 수정할 예정이다. 즉 이번 이슈에서 만든 `ResponseCitationCommandService`는 Issue 5에서 다시 한 번 손볼 것이 확정적으로 예정되어 있다 — Issue 3에서 Issue 2의 `OllamaClient` 관련 파일들을 다시 수정했던 것과 같은 패턴이다.

---

## 로컬 검증 (실제 수행 기록)

```bash
$ ./gradlew test --tests "*ResponseCitationCommandServiceTest*"
BUILD SUCCESSFUL

$ ./gradlew build -x test
BUILD SUCCESSFUL

$ ./gradlew test   # 전체 테스트 스위트 — 회귀 없는지 확인
BUILD SUCCESSFUL
```

---

## 에러 케이스 정리

`ResponseCitationCommandService`는 예외를 던지지 않는다 — 저장 로직 자체가 실패하는 경우(DB 장애 등)는 이 이슈의 관심사가 아니다.

| 상황 | 처리 |
|---|---|
| `candidates`가 빈 리스트 | 빈 리스트로 `saveAll()` 호출 — 예외 없이 정상 종료 (실제로 이 메서드를 호출할지 말지는 Issue 5의 `RagFacade`가 판단) |
| `candidates`에 chunk가 여러 개 | 순서대로 `citation_order` 1부터 부여, 각각 개별 `ResponseCitation` row로 저장 |

---

## 설계 결정 요약

**`SearchResultCommandService.saveAll()`을 그대로 본뜬 구현**: 같은 유형의 문제(후보 리스트 → 엔티티 배치 저장)에 이미 검증된 패턴이 있어서 새로 고안하지 않았다.

**클래스명을 이슈 설명의 "CitationService"에서 `ResponseCitationCommandService`로 변경**: 기존 Command 서비스 네이밍 컨벤션(`SearchQueryCommandService`, `SearchResultCommandService`, `RagResponseCommandService`)과 일관되게 맞췄다. 패키지 위치도 `domain/rag/service/command/`로 `RagResponseCommandService`와 나란히 뒀다.

**citation 순서/라벨을 `PromptBuilder`와 동일하게 재사용**: 프롬프트에 인용된 번호와 저장되는 citation 번호가 항상 일치하도록, 별도 재계산 없이 같은 리스트/같은 순서를 공유한다.

**`search_result_id`를 의도적으로 비워둠**: 검색 블록 코드(`SearchResultCommandService`) 수정이 필요한 작업이라, 검색 결과와 RAG 응답을 실제로 통합하는 Issue 5로 범위를 미뤘다. `nullable` 컬럼이라 스키마 제약을 어기지 않는다.

---

## 남은 이슈 / TODO

### 코드
- `ResponseCitationCommandService`는 아직 어디에서도 호출되지 않는 독립 컴포넌트 — Issue 5에서 `RagFacade`가 실제로 연결한다.
- `search_result_id` 채우기 — Issue 5에서 `SearchResultCommandService.saveAll()`을 `List<SearchResult>` 반환으로 바꾼 뒤 연결 (위 "의도적으로 미룬 부분" 참고).
- (코드리뷰에서 논의된 항목, Issue 3 PR) `RagResponseCommandService.createFailed()`의 `REQUIRES_NEW` 트랜잭션 경계에 대한 Spring 통합 테스트는 검색 블록의 `SearchQueryCommandService.markFailed()`와 동일하게 아직 없음 — 두 군데를 한 번에 정리하는 별도 이슈로 고려.

### 다음 단계
Issue 5 — `RagFacade` 신설. `SearchFacade`와 별도 트랜잭션으로 분리해 `PromptBuilder → OllamaClient → RagResponseCommandService → ResponseCitationCommandService`를 순서대로 호출하도록 묶고, `SearchController`가 `searchFacade.search()` 이후 `ragFacade.generate(...)`를 호출하도록 연결한다. 검색 결과 0건(NO_CONTEXT) 판단도 이 단계에서 들어간다. `SearchResponse`에 `answer`/`citations` 필드를 추가해 `POST /search` 최종 응답을 완성한다.
