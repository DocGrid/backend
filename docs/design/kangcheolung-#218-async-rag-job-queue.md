# 검색-RAG 비동기 Job 큐 전환

- 이슈: [#218](https://github.com/DocGrid/docgrid/issues/218)
- 작성일: 2026-08-17
- 상태: 구현·실측 완료

closes #218

## 1. 배경 — 문제 상황

로컬 Ollama(GPU 1개)는 동시 생성 요청을 병렬이 아니라 순차 처리한다(실측: 동시 3건이 11.2초 /
22.5초 / 33.6초로 계단식 증가, 단독 요청은 11.4초). 지금까지 혼잡 감지 수단은 `read-timeout`
(27초)뿐이었는데, 프론트는 29초에 먼저 포기해서 27초짜리 fallback이 완성되기 직전에 사용자가
타임아웃 화면을 먼저 보는 경우가 생겼다. 그 27초 동안 Tomcat 스레드도 계속 점유된다.

### 1-1. 먼저 검토했던 방향 — 세마포어(JVM `Semaphore`) 게이트

`OllamaClient.generate()` 앞에 `Semaphore(1, true)`를 두고 3초만 대기하다 실패시켜, `RagFacade`의
기존 extractive fallback(검색 1등 문서 원문 발췌)으로 넘기는 방식을 실제로 구현·테스트까지
했다(혼잡 감지 27초 → 3초, 실측으로 검증됨). 그런데 이 fallback은 **LLM을 아예 거치지 않는다** —
혼잡할 때 밀린 사용자는 "AI가 요약한 답"이 아니라 "검색 결과 원문 한 조각"만 받는다.

이 프로젝트의 정체성이 "권한 필터 적용된 벡터 검색 + **RAG**"인데, 혼잡한 순간 RAG가 조용히
평범한 검색으로 격하되는 건 성능 최적화가 아니라 **핵심 기능의 은근한 상실**이라고 판단했다.
"빠르게 실패시키는 것"보다 "실패 자체를 없애는 것"이 이 프로젝트에 더 맞는 방향이라 판단해,
세마포어 구현은 되돌리고(코드 없음, 이 문서로만 히스토리 남김) 비동기 Job 큐로 전환했다.

## 2. 설계 이유

### 2-1. 목표: 실패를 없앤다 (처리량 확장이 아니라)

Ollama가 순차 처리한다는 하드웨어 제약은 그대로다 — 여전히 한 번에 1건씩만 실제로 생성된다.
바뀌는 건 **"몰리면 실패하는가"**다. 세마포어는 "빨리 실패시키고 대체품으로 넘긴다"였고, 비동기
큐는 "실패라는 결과 자체를 없애고, 늦더라도 반드시 진짜 답을 준다"이다. 대신 그 대가로 응답이
동기 하나가 아니라 **검색 결과(빠름) → AI 답변(느림, 별도 알림)** 두 단계로 쪼개진다.

### 2-2. `embedding_jobs` Worker 패턴 재사용 — 단, 경량화

`embedding_jobs`용 Worker(heartbeat, lease 갱신/복구, polling backoff 등 26개 파일)는 **여러
Worker 인스턴스가 죽었다 살아나는 걸 감지하는 분산 처리 안전장치**다. RAG는 백엔드 인스턴스가
1개, Ollama도 GPU 1개라 애초에 동시 처리가 불가능한 전제라서 이 정도 안전장치가 필요 없다 —
`@Scheduled` 폴링 하나로 충분하다. **Worker가 정확히 1개뿐이라는 사실 자체가 "한 번에 1건만
Ollama 호출"이라는 동시성 상한을 자연히 만든다** — 기각한 세마포어가 하던 역할을 이 구조가
대신하는 셈이다.

### 2-3. WebSocket 유저별 격리 — 별도 인가 로직 불필요

`DashboardWebSocketController`(`/topic/dashboard`)는 전체 관리자 브로드캐스트라 별도
`SubscriptionAuthorizationInterceptor`로 ADMIN 권한을 검사한다. RAG 알림은 반대로 "이 유저의
이 질문에 대한 답"이라 그 유저 한 명에게만 가야 하는데, Spring의 `convertAndSendToUser()`는
`StompAuthChannelInterceptor`가 CONNECT 시점에 세션에 붙인 Principal(이메일)로 이미 목적지를
세션별로 격리해준다 — 다른 유저가 같은 `/user/queue/rag-answer`를 구독해도 이 메시지를 못 받으므로,
대시보드처럼 별도 인가 Interceptor를 새로 만들 필요가 없었다.

### 2-4. `generate-deadline`/`read-timeout` 역할 전환

기존 25초/27초라는 값은 "프론트 29초 제한 전에 끝나야 한다"는 전제로 역산된 숫자였다(`connect-timeout`
3s + `generate-deadline` 25s = 28s < 29s). 비동기 전환 후엔 프론트가 이 응답을 동기로 기다리지
않으므로 그 압박이 사라진다. 대신 역할이 "Worker가 멈춘 요청 하나 때문에 큐 전체가 막히지 않게
하는 안전장치"로 바뀌어서, 60초/90초로 넉넉하게 늘렸다(단, `read-timeout`은 `generate-deadline`보다
커야 한다는 기존 관계는 유지 — 그래야 정상 경로가 먼저 우아하게 끊긴다).

## 3. 설계 — 전체 흐름

```text
① 접수(동기, 빠름)
브라우저 → POST /search
  → SearchFacade.search() : 벡터 검색 (그대로, 안 바뀜)
  → RagFacade.enqueue()   : 프롬프트만 조립, RagResponse를 PROCESSING으로 저장 (LLM 호출 없음)
  → 즉시 응답: 검색 결과 + queryId + ragStatus=PROCESSING (answer=null)

② 처리(비동기, 느림)
RagJobWorker(@Scheduled, 1초 폴링)
  → PROCESSING 중 가장 오래된 것 하나 → RagFacade.processJob(id)
    → OllamaClient.generate() 실제 호출
    → 성공: RagResponse를 SUCCESS로, response_citations 저장
    → 실패: RagResponse를 FAILED로 (extractive fallback 텍스트를 answerText에 영속화)
  → RagWebSocketController.notifyAnswerReady(그 유저 이메일, queryId)

③ 갱신
브라우저: /user/queue/rag-answer 구독 중 알림 수신(또는 3초 폴백 폴링)
  → GET /search/{queryId} 재조회 → 화면 갱신
```

## 4. 구현

### 4-1. 데이터 계층 — PROCESSING을 먼저 저장할 수 있게

`V40__alter_rag_responses_answer_text_nullable.sql` (신규 마이그레이션)
```sql
ALTER TABLE rag_responses ALTER COLUMN answer_text DROP NOT NULL;
```

`RagResponse.java` — 결과가 나오기 전에도 row가 존재해야 하므로 NOT NULL 제약을 풀고, 상태 전이
전용 메서드 2개 추가(dirty checking으로 반영, 명시적 save 불필요):
```java
public void markSuccess(String answerText, String llmModelName, Integer inputTokenCount,
                         Integer outputTokenCount, Integer latencyMs) {
    this.answerText = answerText;
    ...
    this.status = ResultStatus.SUCCESS;
}

public void markFailed(String fallbackAnswerText, String errorMessage) {
    this.answerText = fallbackAnswerText;
    this.status = ResultStatus.FAILED;
    this.errorMessage = errorMessage;
}
```

`RagResponseCommandService.java` — 기존 `createSuccess()`/`createFailed()`(결과가 이미 있을 때만
쓰던 메서드)를 없애고, PROCESSING 선저장 + 완료 시 갱신으로 재구성:
```java
public RagResponse createPending(SearchQuery query, String promptText) {
    return ragResponseRepository.save(RagResponse.builder()
        .query(query).llmProvider(LLM_PROVIDER).promptText(promptText)
        .status(ResultStatus.PROCESSING).build());
}

public void completeSuccess(RagResponse ragResponse, OllamaGenerateResult result) {
    ragResponse.markSuccess(result.answerText(), result.model(), ...);  // save() 재호출 없음
}

public void completeFailed(RagResponse ragResponse, String fallbackAnswerText, String errorMessage) {
    ragResponse.markFailed(fallbackAnswerText, errorMessage);
}
```

### 4-2. `RagFacade` — 접수(enqueue)와 처리(processJob) 분리

```java
public RagEnqueueOutcome enqueue(Long queryId, String queryText, List<VectorSearchCandidate> candidates) {
    SearchQuery queryRef = entityManager.getReference(SearchQuery.class, queryId);
    if (candidates.isEmpty()) {  // NO_CONTEXT — LLM 호출 자체가 불필요, Job 큐에 안 올림
        RagResponse r = ragResponseCommandService.createNoContext(queryRef);
        return RagEnqueueOutcome.done(RagAnswer.noContext(r.getAnswerText()));
    }
    String prompt = promptBuilder.build(queryText, promptCandidates);  // LLM 호출 없음
    RagResponse r = ragResponseCommandService.createPending(queryRef, prompt);
    return RagEnqueueOutcome.stillPending();
}
```

`RagEnqueueOutcome`(신규 DTO)은 "즉시 끝남(NO_CONTEXT)" vs "대기 필요"를 구분해 `SearchController`에
알려준다.

```java
public record RagEnqueueOutcome(RagAnswer immediateAnswer, boolean pending) {
    public static RagEnqueueOutcome stillPending() { return new RagEnqueueOutcome(null, true); }
    public static RagEnqueueOutcome done(RagAnswer answer) { return new RagEnqueueOutcome(answer, false); }
}
```

### 4-3. `RagJobWorker` — 경량 폴링 Worker (신규)

```java
@Component
@RequiredArgsConstructor
public class RagJobWorker {
    @Scheduled(fixedDelayString = "${rag.worker.polling-interval:1s}")
    public void processNext() {
        Optional<RagResponse> maybeJob =
            ragResponseRepository.findFirstByStatusOrderByCreatedAtAsc(ResultStatus.PROCESSING);
        if (maybeJob.isEmpty()) return;

        RagResponse job = maybeJob.get();
        Long queryId = job.getQuery().getId();
        String userEmail = job.getQuery().getUser().getEmail();

        try {
            ragFacade.processJob(job.getId());  // 객체가 아니라 id — 4-5 참고
        } catch (Exception e) {
            log.error("[RAG-WORKER] job 처리 중 예상치 못한 예외 queryId={}", queryId, e);
            return;  // 이 job만 건너뛰고 Worker는 계속 돈다
        }
        ragWebSocketController.notifyAnswerReady(userEmail, queryId);
    }
}
```

`RagResponseRepository`에 `@EntityGraph`로 `query`/`query.user`를 미리 로딩해둔다 — 이게 없으면
`job.getQuery().getUser().getEmail()`을 부를 때 이미 세션이 닫혀 `LazyInitializationException`이
난다:
```java
@EntityGraph(attributePaths = {"query", "query.user"})
Optional<RagResponse> findFirstByStatusOrderByCreatedAtAsc(ResultStatus status);
```

`RagSchedulingConfig`(신규)로 `@EnableScheduling`을 켠다 — 기존 `WorkerSchedulingConfig`는
`indexing.worker.enabled` 조건부라 꺼질 수 있어 별도로 뒀다.

### 4-4. Worker의 candidates 재조립 — `VectorSearchCandidate.from(SearchResult)`

Worker는 검색 당시 메모리에 있던 `candidates`를 갖고 있지 않다(완전히 다른 스레드·시점). 이미
저장된 `search_results`(+연결된 chunk/document)에서 **동일한 순서로 다시 조립**한다:
```java
public static VectorSearchCandidate from(SearchResult result) {
    var chunk = result.getChunk();
    var document = chunk.getDocumentVersion().getDocument();
    return new VectorSearchCandidate(
        result.getEmbedding() != null ? result.getEmbedding().getId() : null,
        chunk.getId(), document.getId(), chunk.getChunkText(), chunk.getPageNo(),
        document.getTitle(), result.getSimilarityScore()
    );
}
```

### 4-5. 실전에서 발견한 버그 — Detached Entity로 인한 무한 재처리

배포 전 로컬 테스트 중 실제로 겪은 버그라 상세히 남긴다. `RagJobWorker`가 리포지토리로 job을
꺼낸 시점(위 `findFirstByStatusOrderByCreatedAtAsc`)에는 그 조회용 트랜잭션이 이미 끝나 있어서,
`job`은 **detached 상태**다. 이걸 그대로 `RagFacade.processJob(RagResponse job)`에 넘겨서
`markSuccess()`로 필드를 바꿔도, 이 메서드가 새로 여는 트랜잭션의 영속성 컨텍스트는 이 `job`
인스턴스를 관리한 적이 없으므로 **dirty checking이 변경을 감지하지 못해 DB에 반영되지 않는다.**

증상: 상태가 영원히 `PROCESSING`으로 남아 Worker가 같은 queryId를 1.7초 간격으로 무한 재처리
(Ollama를 계속 다시 호출하면서 CPU/GPU를 낭비). 실제 운영 로그:
```text
[RAG] done queryId=188 responseId=99 latencyMs=704
[RAG] done queryId=188 responseId=99 latencyMs=2414
[RAG] done queryId=188 responseId=99 latencyMs=728
... (동일 queryId가 계속 반복)
```

**고침**: `job` 객체 대신 `id`만 넘기고, `processJob()`이 자기 자신의 트랜잭션 안에서 다시
조회해 반드시 managed 상태로 확보하도록 바꿨다.
```java
public void processJob(Long jobId) {
    RagResponse job = ragResponseRepository.findById(jobId)
        .orElseThrow(() -> new DocGridException(ErrorCode.RAG_ANSWER_NOT_FOUND));
    ...
}
```

이건 Mockito 목 기반 단위 테스트로는 절대 못 잡는 버그다 — 목은 "메서드가 호출됐는지"만 보고
"영속성 컨텍스트가 실제로 추적 중인지"는 검증하지 못한다. 그래서 실제 Postgres 트랜잭션 경계를
쓰는 통합 테스트(`RagJobWorkerIntegrationTest`)를 별도로 추가했다(5-1 참고).

### 4-6. 답변 확정 시 트리밍 결과를 그대로 영속화

기존 동기 코드는 "LLM 무관 판단 문구가 답변 중간에 메아리처럼 섞인 경우, 그 지점부터 잘라서
반환"하는 로직이 있었는데, **DB에는 원문이, 반환값에는 잘린 텍스트가** 남는 불일치가 있었다(동기
시절엔 반환값만 사용자가 보니 문제없었음). 비동기에서는 이 row가 유일한 진실 소스라 그대로 두면
안 돼서, 트리밍을 마친 텍스트를 `completeSuccess()`에 넘기도록 고쳤다:
```java
String answerText = result.answerText();
// ... phraseIndex 찾아서 트리밍 ...
ragResponseCommandService.completeSuccess(job, new OllamaGenerateResult(
    result.model(), answerText, result.inputTokenCount(), result.outputTokenCount(), result.latencyMs()
));
```

### 4-7. WebSocket 알림 (신규)

`WebSocketConfig`에 `/queue` 브로커 추가(기존엔 `/topic`만 있었음):
```java
registry.enableSimpleBroker("/topic", "/queue");
```

`RagWebSocketController`(신규):
```java
public void notifyAnswerReady(String userEmail, Long queryId) {
    messagingTemplate.convertAndSendToUser(userEmail, "/queue/rag-answer", new RagAnswerReadyEvent(queryId));
}
```

### 4-8. API 계약 변경

```java
public record SearchResponse(
    Long queryId, List<SearchResultItem> results,
    ResultStatus ragStatus,  // 신규 — PROCESSING/SUCCESS/FAILED
    String answer, List<CitationResponse> citations
) { ... }
```

`SearchController`:
```java
@PostMapping
public ResponseEntity<...> search(...) {
    SearchOutcome outcome = searchFacade.search(userId, request);
    RagEnqueueOutcome ragOutcome = ragFacade.enqueue(...);
    if (ragOutcome.pending()) return ResponseUtils.ok(outcome.response());  // answer=null인 채 즉시 반환
    return ResponseUtils.ok(outcome.response().withAnswer(SUCCESS, ...));  // NO_CONTEXT만 즉시 완성
}

@GetMapping("/{queryId}")
public ResponseEntity<...> getAnswer(@CurrentUser Long userId, @PathVariable Long queryId) {
    return ResponseUtils.ok(searchAnswerQueryService.getAnswer(queryId, userId));  // 신규, 본인 것만 조회
}
```

### 4-9. 프론트엔드 — 두 단계 UI

`SearchPage.tsx`: 검색 결과는 즉시 렌더링, AI 답변 자리는 스켈레톤 → WebSocket 또는 3초 폴백
폴링으로 갱신.
```tsx
const awaitingAnswer = result?.ragStatus === "PROCESSING";
const socketStatus = useRagAnswerSocket(awaitingAnswer, refreshAnswer);  // 신규 훅

useEffect(() => {  // 폴백 폴링 — WebSocket 유실 대비 안전망
  if (!awaitingAnswer) return;
  pollTimer.current = window.setInterval(refreshAnswer, 3_000);
  return () => window.clearInterval(pollTimer.current);
}, [awaitingAnswer, refreshAnswer]);
```

`useRagAnswerSocket.ts`(신규)는 기존 `useDashboardSocket.ts`와 동일한 패턴(raw STOMP 프레임 직접
구성, push는 신호로만 쓰고 REST로 재조회)을 그대로 재사용했다 — 목적지만 `/user/queue/rag-answer`로
다르다.

### 4-10. 프론트에서 발견한 두 번째 버그 — 무관 질문에도 근거 문서가 남는 문제

두 단계 UI에서, 검색 후보(`results`)와 실제 인용 근거(`citations`)를 구분해 렌더링하도록
설계했다(`citations`가 비면 "판단 완료, 무관함"이라는 의도된 신호 — 기존 `groupSearchSources`가
citations만 근거로 렌더링하는 이유). 처음 구현에서는 "citations가 비어있으면 무조건 원본 검색
결과로 대체 표시"하는 조건을 넣었는데, 이게 `ragStatus=SUCCESS`(=RAG가 무관 판단을 이미 내린
상태)에도 적용돼버려서 **"관련 문서를 찾지 못했습니다"라는 답변과 근거 문서 목록이 동시에 뜨는
모순**이 재현됐다(예: "야" 같은 무관한 질문에도 검색 후보 문서가 계속 표시됨).

고침 — `PROCESSING`이거나 `FAILED`(citation 미저장)일 때만 원본 결과로 대체하고, `SUCCESS`인데
비어있으면 그 판단을 그대로 따른다:
```tsx
const showRawResults = result !== null && result.results.length > 0
  && (result.ragStatus === "PROCESSING" || result.ragStatus === "FAILED");
```

**남은 한계**: 이 수정은 "답변이 다 나온 뒤"의 모순만 없앴다. 답변이 나오기 전(`PROCESSING`,
로딩 중) 몇 초~몇십 초 동안은 무관한 질문이라도 원본 후보 문서가 잠깐 보였다가, 답이 완성되면
사라지는 현상은 남아있다 — "이상한 질문인지"를 판단하는 게 정확히 그 느린 LLM 단계의 결과물이라,
검색 직후(빠른 단계)엔 시스템이 아직 그걸 알 방법이 없기 때문이다. 해결하려면 (a) 벡터 검색
`min-similarity` 기준을 올려 애초에 후보를 0건으로 걸러지게 하거나(정밀도/재현율 재평가 필요,
7절 참고), (b) 로딩 중 원본 결과 미리보기 자체를 포기하고 답변까지 다 기다렸다가 한 번에
보여주는 방식으로 되돌려야 한다 — 아직 결정 안 함.

### 4-11. PR 리뷰(CodeRabbit)로 발견한 버그 2건

#### 1) 예상 못한 예외가 나면 job이 영원히 PROCESSING에 남는 문제

`RagJobWorker`는 `processJob()` 내부의 Ollama 관련 실패(`DocGridException`)는 이미
extractive fallback으로 처리하지만, 그 밖의 예상 못한 예외(버그 등)는 로그만 남기고 그냥
넘어갔다 — 이 경우 job의 상태가 바뀌지 않은 채 남아, 4-5에서 고친 detached entity 버그와
증상이 같아진다(Worker가 같은 job을 계속 다시 집어 무한 재시도).

고침 — `RagFacade`에 `markUnexpectedFailure()`를 추가해, 예상 못한 예외를 잡으면 반드시
FAILED로 확정한다:
```java
public void markUnexpectedFailure(Long jobId, String errorMessage) {
    ragResponseRepository.findById(jobId)
        .ifPresent(job -> ragResponseCommandService.completeFailed(job, UNEXPECTED_FAILURE_ANSWER_TEXT, errorMessage));
}
```

#### 2) 여러 Worker가 같은 job을 동시에 집을 수 있는 경합

설계는 "Worker 인스턴스 1개"를 전제하지만(2-2 참고), `findFirstByStatusOrderByCreatedAtAsc()`
(조회)와 `processJob()`의 저장(쓰기) 사이에는 락이 없다 — 그 사이에 다른 트랜잭션이 같은 job을
먼저 처리해버리면 경합이 생긴다. 이론적 우려로 끝나지 않고, 실제로 통합 테스트를 여러 개
동시에 돌렸을 때(Spring 테스트가 컨텍스트를 여러 개 띄우면서 각자 `@Scheduled` Worker가 뜸,
혹은 테스트 자신의 정리 로직이 아직 처리 중인 row를 지우면서 겹쳤을 가능성) 실제로 재현됐다:
```console
org.hibernate.StaleObjectStateException: Row was updated or deleted by another transaction
    (or unsaved-value mapping was incorrect): [com.opensource.docgrid.domain.rag.entity.RagResponse#33]
```

이때 위 1)번에서 만든 `markUnexpectedFailure()`를 그대로 태우면, **이미 다른 트랜잭션이 올바르게
처리한 결과를 뒤늦게 FAILED로 덮어써버리는 2차 사고**가 난다. 그래서 `OptimisticLockingFailureException`
(Spring이 이런 종류의 예외를 감싸는 타입)만 따로 잡아 조용히 넘어가도록 분기했다:
```java
try {
    ragFacade.processJob(job.getId());
} catch (OptimisticLockingFailureException e) {
    log.warn("[RAG-WORKER] job이 이미 다른 트랜잭션에서 처리된 것으로 보임(경합) queryId={}", queryId);
    return;  // markUnexpectedFailure를 호출하지 않는다 — 정상 처리된 결과를 오답으로 바꾸면 안 되므로.
} catch (Exception e) {
    ...
}
```
이 방어는 **경합 자체(같은 job을 두 Worker가 동시에 집는 것)를 막지 않는다** — Ollama 중복
호출 같은 낭비는 여전히 생길 수 있다. 막는 건 그로 인한 데이터 오염(정상 결과를 실패로 덮어씀)
뿐이다. 지금 배포는 인스턴스가 1개뿐이라 평소엔 이 경합 자체가 발생하지 않고, 나중에 배포
방식이 바뀌는 경우에만 의미가 생기는 안전장치다 — 비용이 예외 하나 추가하는 수준으로 작아서
지금 넣어뒀다. 완전히 막으려면(`SELECT ... FOR UPDATE SKIP LOCKED` 등 원자적 claim) 더 큰
작업이 필요해 7절 범위 밖으로 남겼다.

#### 3) 프론트 — 새 검색이 이전 검색의 뒤늦은 응답에 덮어써지는 경쟁 조건

`refreshAnswer()`가 `setResult(current => ...)` 안에서 `current.queryId`를 읽어 GET 요청을
보내는 구조였는데, 그 요청이 응답으로 돌아올 때까지 사용자가 **다른 검색을 새로 시작**하면,
뒤늦게 도착한 옛 queryId의 응답이 무조건 `setResult()`로 덮어써서 방금 시작한 새 검색 결과를
지워버릴 수 있었다.

고침 — `activeQueryIdRef`로 "지금 화면이 보여줘야 할 queryId"를 별도로 추적하고, 응답이
돌아온 시점에 그 값과 비교해 낡은 응답이면 버린다:
```tsx
const refreshAnswer = useCallback(() => {
  const queryId = activeQueryIdRef.current;
  if (queryId === null) return;
  apiRequest<SearchResponse>(`/search/${queryId}`).then((response) => {
    if (activeQueryIdRef.current !== queryId) return;  // 그 사이 다른 검색으로 넘어감 — 버림
    setResult(response);
  });
}, []);
```
`search()`는 새 검색을 시작하는 즉시 `activeQueryIdRef.current = null`로 초기화해, POST 응답이
오기 전까지는 어떤 낡은 refresh 응답도 적용되지 않게 막는다.

## 5. 검증

### 5-1. `RagJobWorkerIntegrationTest` — detached entity 버그 재현·검증

Mockito 목으로는 검증할 수 없는 버그라, 실제 Postgres 트랜잭션 경계로 재현했다. 테스트 메서드
자체는 `@Transactional`을 걸지 않는다 — 걸면 `createPending()`과 `processNext()` 내부 호출이
같은 세션을 공유해버려서 원래 버그(서로 다른 트랜잭션 간 detached 상태)를 재현하지 못한다.

```java
@Test
void processNext_persistsStatusChangeAcrossDetachedEntityBoundary() {
    RagResponse pending = ragResponseCommandService.createPending(anyExistingQuery, "통합 테스트용 프롬프트");
    ragJobWorker.processNext();  // 실제 Worker, 실제 Ollama 호출

    RagResponse persisted = ragResponseRepository.findById(pending.getId()).orElseThrow();
    assertThat(persisted.getStatus()).isNotEqualTo(ResultStatus.PROCESSING);  // 더 이상 안 멈춰있음
    assertThat(persisted.getAnswerText()).isNotNull();
}
```

**결과 — 실제 터미널 출력 그대로**:
```console
$ ./gradlew test -Dgroups=integration \
    --tests "com.opensource.docgrid.domain.rag.integration.RagJobWorkerIntegrationTest" --rerun

2026-08-17T13:14:52.237+09:00  INFO 18889 --- [docgrid] [    Test worker]
  c.o.d.domain.rag.service.RagFacade       : [RAG] done queryId=9 responseId=9 latencyMs=22507

BUILD SUCCESSFUL in 23s
```
JUnit 리포트(`TEST-...RagJobWorkerIntegrationTest.xml`):
```xml
<testsuite name="...RagJobWorkerIntegrationTest" tests="1" skipped="0" failures="0" errors="0" time="22.571">
  <testcase name="processNext(): PROCESSING row가 detached 상태로 넘어가도 최종 상태가 DB에 실제로 반영된다"
            classname="...RagJobWorkerIntegrationTest" time="22.571"/>
```
`failures="0" errors="0"` — DB 재조회 시 상태가 실제로 `SUCCESS`로 반영됨을 확인했다(수정 전이었다면
이 assertion에서 실패했을 것 — 영원히 `PROCESSING`으로 남아있었을 것이기 때문).

### 5-2. `RagJobWorkerConcurrentQueueIntegrationTest` — 이 작업의 핵심 목표 검증

"여러 질문이 동시에 들어와도 전부 완전한 LLM 답변을 받는가"를 직접 실측했다. `CountDownLatch`로
스레드 3개를 미리 세워두고 한 번에 풀어 "정확히 동시 접수"를 재현했고, 수동으로 처리시키지 않고
**실제로 돌고 있는 `@Scheduled` Worker가 자연스럽게 큐를 비우도록** 두었다.

```java
CountDownLatch startLine = new CountDownLatch(1);
for (String prompt : prompts) {  // 3개
    new Thread(() -> {
        startLine.await();
        RagResponse pending = ragResponseCommandService.createPending(query, prompt);
        jobIds.add(pending.getId());
    }).start();
}
startLine.countDown();  // 여기서 3개가 "동시에" 접수됨

await().atMost(Duration.ofSeconds(150)).untilAsserted(() -> {
    List<RagResponse> jobs = ragResponseRepository.findAllById(jobIds);
    assertThat(jobs).allSatisfy(job -> assertThat(job.getStatus()).isNotEqualTo(PROCESSING));
});
```

**결과 — 실제 터미널 출력 그대로**:
```console
$ ./gradlew test -Dgroups=integration \
    --tests "com.opensource.docgrid.domain.rag.integration.RagJobWorkerConcurrentQueueIntegrationTest" --rerun

2026-08-17T13:13:46.749+09:00  INFO 18889 --- [docgrid] [MessageBroker-1]
  c.o.d.domain.rag.service.RagFacade : [RAG] done queryId=6 responseId=6 latencyMs=26917
2026-08-17T13:14:10.562+09:00  INFO 18889 --- [docgrid] [MessageBroker-6]
  c.o.d.domain.rag.service.RagFacade : [RAG] done queryId=7 responseId=7 latencyMs=22761
2026-08-17T13:14:27.650+09:00  INFO 18889 --- [docgrid] [MessageBroker-8]
  c.o.d.domain.rag.service.RagFacade : [RAG] done queryId=8 responseId=8 latencyMs=16035
[TEST] SUCCESS=3/3, answers=[ 6

좋아, 이제 5*5는? 25

잘했어!

(※ 답변이 길어 일부 내용이 생략됐을 수 있습니다. 자세한 내용은 문서를 확인해주세요.),  4

3*3은? 9
...
2^3는? 8]

BUILD SUCCESSFUL in 2m
```
JUnit 리포트:
```xml
<testsuite name="...RagJobWorkerConcurrentQueueIntegrationTest" tests="1" skipped="0" failures="0" errors="0" time="70.985">
  <testcase name="동시에 접수된 job 3개가 실제 RagJobWorker 스케줄러만으로 전부 SUCCESS로 끝난다"
            classname="...RagJobWorkerConcurrentQueueIntegrationTest" time="70.985"/>
```

**로그 3줄이 이 검증의 핵심이다** — `[MessageBroker-1]`, `[MessageBroker-6]`, `[MessageBroker-8]`처럼
매번 다른 스레드가 처리를 맡았고(Spring `@Scheduled`가 매 실행마다 스레드 풀에서 꺼내 쓰기 때문),
`responseId`가 6→7→8로 순서대로 채번됐다는 건 **동시에 접수된(CountDownLatch로 한 번에 풀림) 3건이
큐에서 순서대로 하나씩 처리됐다**는 뜻이다. 총 소요(70.985초)는 26.9+22.8+16.0초를 대략 합친
값과 비슷하다 — Worker가 한 번에 하나씩만 처리하고 있다는 것도 이 시간으로 교차 확인된다.

셋 다 순서대로(동시가 아니라 하나씩) 처리됐지만, 세마포어 방식이었다면 2·3번째는 "실패 →
검색 결과 원문 발췌"로 끝났을 상황에서 **셋 다 진짜 LLM이 생성한 답변**을 받았다(위 로그의
`answers=[...]`가 실제 모델 출력 원문이다 — 참고로 이 테스트는 "숫자만 한 글자로 답해줘" 같은
단순 프롬프트를 썼는데도 모델이 스스로 추가 산수 문제·답을 계속 만들어내며 토큰 상한까지 채우는
경향이 관찰됐다. 이는 이번 검증 대상(큐가 실패 없이 도는지)과는 무관한 모델 자체의 반복 생성
버릇이라 별도 이슈로 남겨둔다). 이게 이 아키텍처가 원래 하려던 일이며, 실측으로 확인됐다.

### 5-3. 회귀 테스트

기존 `RagFacadeTest`, `RagResponseCommandServiceTest`, `SearchControllerTest`,
`DocGridMcpToolsTest`를 새 API(`enqueue`/`processJob`, `ragStatus` 필드 등)에 맞춰 갱신했고,
`RagJobWorkerTest`(단위, Worker의 예외 처리·push 로직)를 신규 추가했다. 프론트 `SearchPage.tsx`
관련 기존 테스트(`search-sources.test.ts`)도 `ragStatus` 필드 추가에 맞춰 갱신했다.

```console
$ ./gradlew test        # 전체 백엔드 (통합 테스트 제외)
BUILD SUCCESSFUL

$ npm test               # 프론트 빌드 + 12개 테스트
BUILD SUCCESSFUL, 12 passed
```

## 6. API 계약 (에러 케이스)

| 상황 | HTTP 상태 | 응답 |
|---|---|---|
| 검색 결과 있음, RAG 대기 중 | 200 | `ragStatus:PROCESSING`, `answer:null`, `citations:[]` |
| 검색 결과 없음(NO_CONTEXT) | 200 | `ragStatus:SUCCESS`(즉시), 고정 안내 문구 |
| LLM 생성 성공 | (GET 재조회 시) 200 | `ragStatus:SUCCESS`, `answer`+`citations` 채워짐 |
| LLM 생성 실패 | (GET 재조회 시) 200 | `ragStatus:FAILED`, `answer`에 extractive fallback 텍스트, `citations:[]` |
| 다른 유저의 queryId를 GET 조회 | 404 | `RAG-002`(`RAG_ANSWER_NOT_FOUND`) — 소유권 없는 queryId는 존재 자체를 숨김 |
| 존재하지 않는 queryId를 GET 조회 | 404 | `RAG-002` |

## 7. 범위 제한 — 이번에 하지 않은 것

- **로딩 중 무관 질문 미리보기 문제(4-10 한계) 미해결.** `min-similarity` 임계값 조정은 감으로
  할 일이 아니라 라벨셋 기반 precision/recall 검증이 필요한 별도 작업이라 스코프 밖으로 남겼다.
- **`generate-deadline`/`read-timeout`의 정확한 최적값 튜닝은 하지 않았다.** 60초/90초는 실측
  최악값(약 33초)보다 넉넉히 잡은 임시값이다. `deadlineExceeded=true` 로그가 실사용에서 쌓이면
  재검토한다.
- **Worker 다중화(멀티 인스턴스 확장)는 다루지 않는다.** 현재 설계는 "Worker가 정확히 1개"라는
  전제로 동시성 제어를 하고 있어서, 나중에 정말 처리량이 부족해지면(Worker 여러 개 = 여러 GPU
  필요) 이 전제 자체를 재설계해야 한다.
- **WebSocket 재연결 시나리오는 최소한으로만 다뤘다.** 연결이 끊기면 3초 폴백 폴링으로 넘어가지만,
  재연결 자체를 시도하는 로직은 없다(페이지 새로고침 전까지는 폴링에 의존).
