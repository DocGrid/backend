# #286 RAG 답변 생성이 PROCESSING 상태로 무기한 대기하는 문제 수정 (QA-P0-01)

closes #286

---

## 배경

QA 리포트(QA-P0-01): "AI 답변이 77초 이상 생성 중에서 멈춤."

`#218`(검색-RAG 비동기 Job 큐 전환) 이후 검색(`POST /search`)은 즉시 끝나고, RAG 최종 답변은
`RagJobWorker`(1초 폴링 `@Scheduled`)가 `rag_responses` 테이블의 PROCESSING row를 하나씩
순서대로 처리한다. 이 구조 자체는 의도된 설계다 — 우리는 오픈소스 라이선스 제약 때문에 폐쇄형
LLM을 못 쓰고 로컬 Ollama(`qwen2.5:7b`, Apache 2.0)를 쓰는데, `3b`가 더 빠르지만 Qwen Research
License(비상업)라 배제했다(`#184`). Ollama는 GPU 1개로만 돌아가 동시 처리가 원천적으로
불가능하고, `RagJobWorker` 인스턴스를 정확히 1개만 두는 것 자체가 이 동시성 상한(=1)을
자연스럽게 강제한다(`RagJobWorker` 클래스 Javadoc 참고).

문제는 이 순차 대기 시간에 **상한이 없다**는 것이다.

- `ollama.generate-deadline`(60s)은 `OllamaClient.readStream()` 안에서 NDJSON 한 줄을 다 읽은
  **직후**에만 검사된다. `BufferedReader.readLine()` 자체가 다음 바이트를 기다리며 블로킹되면
  이 검사가 실행되지 않는다.
- 이 경우를 막는 최후 방어선은 전송 계층의 `ollama.server.read-timeout`(90s)뿐이다. 즉 정상
  시나리오라도 한 건당 최악 90초 가까이 걸릴 수 있고, 그 앞에 밀린 요청이 있으면 그만큼 더
  쌓인다.
- 프론트(`SearchPage.tsx`)는 `ragStatus === "PROCESSING"`인 동안 대기 시간과 무관하게 항상
  같은 "AI가 답변을 정리하고 있어요…" 문구만 띄웠다 — 사용자 입장에서는 화면이 멈춘 것처럼
  보인다.

모델을 `qwen2.5:3b`로 낮추는 방안은 검토했으나 라이선스 문제로 배제했고, 애초에 "느려서"가
아니라 "상한이 없어서" 생기는 문제라 모델을 바꿔도 근본 해결이 안 된다.

**목표**: 폐쇄형 LLM 없이 GPU 1개로 순차 처리해야 하는 제약은 그대로 두되, "얼마나 걸리든
사용자는 유한 시간 안에 반드시 어떤 응답(정상 답변이든 대체 답변이든)을 받는다"는 상한을
만든다.

---

## 전체 흐름

```text
POST /search
     │
     ▼
RagFacade.enqueue()  ← 검색 직후 동기 호출, LLM 호출 없음. PROCESSING으로 저장하고 즉시 반환

     ... 비동기로 시간 경과 ...

     ┌─────────────────────────────┐      ┌──────────────────────────────────┐
     │ RagJobWorker (1초 폴링)       │      │ RagJobTimeoutSweeper (15초 폴링, 신규) │
     │ 가장 오래된 PROCESSING 1건을   │      │ stale-threshold(90s) 넘은 PROCESSING │
     │ 실제로 Ollama 호출해 처리      │      │ job을 찾아 강제 종료 시도            │
     └──────────────┬───────────────┘      └──────────────┬───────────────────┘
                    │                                      │
                    ▼                                      ▼
        completeSuccess / completeFailed      forceFailIfProcessing()
        (일반 UPDATE, dirty checking)         (조건부 벌크 UPDATE: WHERE status='PROCESSING')
                    │                                      │
                    └──────────────┬───────────────────────┘
                                   ▼
                    RagWebSocketController.notifyAnswerReady()
                                   │
                                   ▼
                    프론트: GET /search/{queryId} 재조회 (기존 흐름 그대로)
```

`RagJobWorker`(실제 LLM 호출)와 `RagJobTimeoutSweeper`(타임아웃 감시)는 완전히 독립된
스케줄러다. 서로의 존재를 모르고, 같은 job을 거의 동시에 건드릴 수 있다는 전제 위에서
설계했다 — 아래 "경합 방지 설계" 참고.

---

## 변경 파일 — 백엔드

### 1. `domain/rag/repository/RagResponseRepository.java` — 쿼리 메서드 2개 추가

```java
@EntityGraph(attributePaths = {"query", "query.user"})
List<RagResponse> findByStatusAndCreatedAtBefore(ResultStatus status, LocalDateTime threshold);

@Modifying(clearAutomatically = true)
@Query("UPDATE RagResponse r SET r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.FAILED, "
     + "r.answerText = :answerText, r.errorMessage = :errorMessage "
     + "WHERE r.id = :id AND r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.PROCESSING")
int forceFailIfProcessing(@Param("id") Long id, @Param("answerText") String answerText,
                          @Param("errorMessage") String errorMessage);
```

- `findByStatusAndCreatedAtBefore`: "얼마나 오래 PROCESSING이었는지" 판단에 새 컬럼을 만들지
  않고 이미 있던 `BaseEntity.createdAt`을 그대로 썼다. PROCESSING row는
  `RagResponseCommandService.createPending()` 시점에 생성되므로, `createdAt`이 곧 "큐에
  들어간 시각"이다. `query`/`query.user`를 `@EntityGraph`로 미리 fetch하는 이유는 기존
  `findFirstByStatusOrderByCreatedAtAsc`와 동일하다 — 스위퍼도 WebSocket 알림을 보내려면
  트랜잭션 밖에서 `query.user.email`에 접근해야 한다.
- `forceFailIfProcessing`: 아래 "경합 방지 설계" 문단에서 상세히 다룬다.

### 2. `domain/rag/service/RagFacade.java` — `failIfStillProcessing()` 추가

```java
public boolean failIfStillProcessing(Long jobId, Long queryId) {
    List<VectorSearchCandidate> candidates = loadCandidates(queryId);
    String fallbackAnswer = candidates.isEmpty()
        ? UNEXPECTED_FAILURE_ANSWER_TEXT
        : buildExtractiveFallbackAnswer(candidates);
    int updated = ragResponseRepository.forceFailIfProcessing(jobId, fallbackAnswer, TIMEOUT_ERROR_MESSAGE);
    return updated > 0;
}
```

새 로직을 거의 짜지 않았다 — `loadCandidates()`/`buildExtractiveFallbackAnswer()`는 이미
Ollama 호출 실패 시 쓰던 extractive fallback 헬퍼(검색 1등 후보 원문을 최대 300자 인용)를
그대로 재사용한다. 그래서 스위퍼가 강제 종료시킨 job은 **LLM 호출 실패 fallback과 화면상
완전히 동일한 답변**을 갖게 된다 — 사용자는 "진짜 LLM이 실패한 건지, 큐가 밀려 강제
종료된 건지" 구분할 수 없고, 구분할 필요도 없다. 반환값(`updated > 0`)으로 "내가 실제로
종료시켰는지, 이미 Worker가 끝낸 걸 만난 건지"를 호출자에게 알려준다.

`candidates.isEmpty()`(→ `UNEXPECTED_FAILURE_ANSWER_TEXT`) 분기는 이론상 도달 불가능한
방어 코드다. `RagFacade.enqueue()`가 candidates 없는 검색은 애초에 PROCESSING을 거치지 않고
NO_CONTEXT로 즉시 끝내버리기 때문에, 스위퍼가 candidates 없는 PROCESSING job을 만나는 건
"검색 결과가 나중에 삭제됨" 같은 극단적 상황뿐이다.

### 3. `domain/rag/service/RagJobTimeoutSweeper.java` (신규 파일)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class RagJobTimeoutSweeper {

    private final RagResponseRepository ragResponseRepository;
    private final RagFacade ragFacade;
    private final RagWebSocketController ragWebSocketController;

    @Value("${rag.worker.stale-threshold:90s}")
    private Duration staleThreshold;

    @Scheduled(fixedDelayString = "${rag.worker.timeout-sweep-interval:15s}")
    public void sweep() {
        LocalDateTime cutoff = LocalDateTime.now().minus(staleThreshold);
        List<RagResponse> staleJobs =
            ragResponseRepository.findByStatusAndCreatedAtBefore(ResultStatus.PROCESSING, cutoff);

        for (RagResponse job : staleJobs) {
            Long queryId = job.getQuery().getId();
            String userEmail = job.getQuery().getUser().getEmail();
            if (ragFacade.failIfStillProcessing(job.getId(), queryId)) {
                log.warn("[RAG-SWEEP] stale job force-failed queryId={} responseId={}", queryId, job.getId());
                ragWebSocketController.notifyAnswerReady(userEmail, queryId);
            }
        }
    }
}
```

`RagJobWorker`와 나란히 같은 패키지에 뒀다. `sweep()` 자체는 `@Transactional`이 아니다 —
여러 stale job 중 하나가 실패해도 나머지 job 처리에 영향을 주지 않도록, `ragFacade.
failIfStillProcessing()` 호출마다 독립된 트랜잭션이 되게 한다(`RagFacade`가 클래스 레벨
`@Transactional`이므로 이 호출 하나하나가 각자 새 트랜잭션이다). 강제 종료에 성공했을
때만 기존 `RagWebSocketController.notifyAnswerReady()`를 그대로 호출한다 — 프론트 입장에서는
`RagJobWorker`가 정상 완료했을 때와 신호가 완전히 동일하다.

### 4. `application.yml`

```yaml
rag:
  worker:
    polling-interval: ${RAG_WORKER_POLLING_INTERVAL:1s}
    # 이 시간 이상 PROCESSING으로 남아있으면(정상 백로그든 실제 hang이든 원인 불문) RagJobTimeoutSweeper가
    # 무기한 대기시키지 않고 강제로 fallback 답변을 채워 종료한다(#286).
    stale-threshold: ${RAG_WORKER_STALE_THRESHOLD:90s}
    # RagJobTimeoutSweeper가 위 stale-threshold 기준을 얼마나 자주 확인할지.
    timeout-sweep-interval: ${RAG_WORKER_TIMEOUT_SWEEP_INTERVAL:15s}
```

---

## 경합 방지 설계 — 왜 조건부 UPDATE가 필요한가

스위퍼가 "90초 넘었으니 강제 종료해야지" 판단하려는 바로 그 순간, `RagJobWorker`가 같은
job을 정상적으로 막 완료해서 SUCCESS로 커밋했을 수 있다. 이 저장소엔 낙관적 락(`@Version`)
필드가 **어디에도 없다**(`RagResponse`/`BaseEntity` 확인 완료). 그래서 엔티티를 그대로
불러와 `save()`하는 방식으로 강제 종료를 구현하면, 두 트랜잭션이 충돌 없이 그냥 "나중에
쓰는 쪽이 이기는" 방식으로 서로를 덮어쓸 수 있다 — 스위퍼가 방금 완성된 정답을 도로
FAILED로 지워버리는 사고가 날 수 있다는 뜻이다.

이를 막기 위해 강제 종료는 엔티티 로드 후 `save()`가 아니라, **`WHERE id=? AND
status='PROCESSING'` 조건이 걸린 벌크 UPDATE**(`forceFailIfProcessing`)로만 실행한다.
`RagJobWorker`가 이미 먼저 끝냈다면 이 UPDATE 시점엔 `status`가 더 이상 PROCESSING이
아니므로 영향받은 행이 0건이 되고, `failIfStillProcessing()`은 `false`를 반환해 아무 알림도
보내지 않는다. SQL의 WHERE절 자체가 "이미 끝난 걸 덮어쓰지 마라"는 안전장치 역할을 한다.

**구현 중 실제로 걸린 문제**: `RagResponseRepositoryTest`(`@DataJpaTest`)로 "이미 SUCCESS인
job에 강제 종료를 걸어도 상태가 그대로여야 한다"를 검증하다가, `clearAutomatically`를 안
넣었을 때 테스트가 실패했다. 벌크 UPDATE는 영속성 컨텍스트(1차 캐시)를 거치지 않고 DB에
직접 실행되기 때문에, 같은 트랜잭션 안에서 이미 로딩해둔 엔티티 인스턴스는 UPDATE 이후에도
여전히 갱신 전 값을 캐시에 들고 있었다 — 재조회(`findById`)해도 DB가 아니라 그 캐시를
돌려줬다. `@Modifying(clearAutomatically = true)`로 UPDATE 직후 영속성 컨텍스트를 비워
해결했다.

---

## 변경 파일 — 프론트

### `frontend/app/features/SearchPage.tsx`

```ts
const LONG_WAIT_NOTICE_MS = 30_000;
const [longWait, setLongWait] = useState(false);

useEffect(() => {
  if (!awaitingAnswer) return;
  const timer = window.setTimeout(() => setLongWait(true), LONG_WAIT_NOTICE_MS);
  return () => {
    window.clearTimeout(timer);
    setLongWait(false);
  };
}, [awaitingAnswer]);
```

대기 30초 초과 시 로딩 문구만 "생각보다 오래 걸리고 있어요. 조금만 더 기다려 주세요…"로
전환한다. WebSocket/폴링/재조회 로직은 하나도 건드리지 않았다 — 백엔드가 이제 PROCESSING을
유한 시간(90초) 안에 반드시 SUCCESS/FAILED로 종료시켜주므로, 프론트는 기존 흐름을 그대로
타면 된다. `ragStatus: FAILED`는 이미 `awaitingAnswer` 분기에서 정상 처리되는 상태였기
때문에(LLM 실패 fallback 때부터), 스위퍼가 만든 FAILED도 별도 분기 없이 그대로 렌더링된다.

효과 리셋을 effect 본문에서 직접 `setState`로 하면 `eslint-plugin-react-hooks`가 "effect
안에서 setState 직접 호출은 cascading render를 유발한다"고 잡아서, cleanup 함수 안에서
리셋하도록 고쳤다.

---

## API 영향 / 에러 케이스 정리

새 API 엔드포인트는 없다 — 기존 `POST /search`, `GET /search/{queryId}` 응답의 `ragStatus`/
`answer`/`citations` 조합에 새 시나리오(스위퍼에 의한 강제 FAILED)가 하나 추가될 뿐이다.

| 상황 | `ragStatus` | `answer` | `citations` | 비고 |
|---|---|---|---|---|
| 검색 후보 없음(NO_CONTEXT) | SUCCESS | 고정 문구("관련 문서를 찾지 못했습니다.") | `[]` | 기존 동작, 변경 없음 |
| Worker가 정상적으로 답변 생성 | SUCCESS | LLM 생성 답변 | 실제 인용 목록 | 기존 동작, 변경 없음 |
| Ollama 호출 자체가 실패(연결 거부 등) | FAILED | extractive fallback(검색 1등 후보 인용) | `[]` | 기존 동작, 변경 없음 |
| **(신규) 90초 넘게 PROCESSING → 스위퍼가 강제 종료** | FAILED | extractive fallback(검색 1등 후보 인용) | `[]` | 위 "Ollama 호출 실패" 행과 **응답이 완전히 동일** — 클라이언트가 원인을 구분할 방법이 없고, 구분할 필요도 없도록 의도적으로 설계 |
| 스위퍼 개입 시점에 Worker가 이미 완료한 경합 상황 | (Worker가 만든 최종 상태 그대로) | (Worker가 만든 답변 그대로) | (Worker가 만든 citations 그대로) | `forceFailIfProcessing`이 영향받은 행 0건 반환 → 스위퍼는 아무 것도 바꾸지 않고 알림도 안 보냄(Worker가 이미 보낸 알림이 유일한 신호) |

---

## 참고: RAG에서 WebSocket을 쓰는 이유

`RagWebSocketController`(`/user/queue/rag-answer`)는 이번 이슈에서 새로 만든 게 아니라
`#218`부터 있던 기존 메커니즘이다. 이번 수정(`RagJobTimeoutSweeper`)도 강제 종료 성공 시
이 기존 push를 그대로 재사용하므로, 왜 이 구조를 쓰는지 여기 같이 정리해둔다.

- **push는 트리거일 뿐, 페이로드가 아니다**: `notifyAnswerReady(userEmail, queryId)`가
  보내는 건 `{ queryId }` 하나뿐이다. 프론트(`useRagAnswerSocket.ts`)는 이 메시지를 받으면
  무조건 `GET /search/{queryId}`로 다시 조회한다 — WebSocket 페이로드 자체를 최종 상태로
  신뢰하지 않고, REST를 항상 유일한 진실 소스로 둔다. 그래서 이번처럼 "누가 답변을
  완료시켰는지"(Worker vs Sweeper)가 프론트 입장에서 전혀 중요하지 않다 — 어느 쪽이 보내든
  같은 재조회 로직을 그대로 탄다.
- **`/queue`(개인용)를 쓰지 `/topic`(전체 브로드캐스트)을 쓰지 않는다**: RAG 답변은 그
  질문을 던진 사용자 한 명에게만 가야 한다. `SimpMessagingTemplate.convertAndSendToUser()`가
  CONNECT 시점에 세션에 부착된 Principal(이메일)로 목적지를 사용자별로 격리해주기 때문에,
  대시보드(`/topic/dashboard`)처럼 별도의 구독 인가 인터셉터(`DashboardSubscriptionAuthorization
  Interceptor`)가 필요 없다 — 다른 사용자는 같은 목적지 문자열을 구독해도 이 메시지를
  받지 못한다.
- **폴링(3초 간격)이 이미 폴백으로 있는데도 WebSocket을 쓰는 이유**: WebSocket 연결이
  끊기거나 실패해도(`socketStatus: "POLLING"`) 3초 폴링이 결국 같은 결과를 가져오므로
  기능적으로는 폴링만으로도 동작한다. WebSocket은 그 위에 얹는 **체감 지연 단축**용이다 —
  평균적으로 폴링 간격(최대 3초)만큼의 지연 없이 답변이 완료되는 즉시(수십~수백ms 내) 화면이
  갱신된다.
- 프론트 구현(수동 STOMP 프레임 조립, 라이브러리 미사용)에 대한 상세는
  `frontend/app/lib/useRagAnswerSocket.ts` 참고.

---

## 테스트

### 신규/변경 파일
- `RagResponseRepositoryTest`(신규, `@DataJpaTest`): `forceFailIfProcessing`이 PROCESSING
  job은 실제로 FAILED로 바꾸고(영향받은 행 1건), 이미 SUCCESS인 job은 절대 덮어쓰지 않는지
  (영향받은 행 0건, 상태 그대로) 실제 PostgreSQL로 검증 — 이번 수정의 핵심 안전장치라
  Mockito로는 증명할 수 없어 이 계층 테스트가 필요했다. `findByStatusAndCreatedAtBefore`가
  상태/시각 조건으로 올바르게 필터링하는지도 함께 검증.
- `RagJobTimeoutSweeperTest`(신규, Mockito 단위 테스트, `RagJobWorkerTest`와 동일한 스타일):
  stale job 없음/있음, `failIfStillProcessing`이 true/false일 때 알림 발송 여부, 여러 건일
  때 전부 처리하는지 검증.
- `RagFacadeTest`: `failIfStillProcessing()` 케이스 3개 추가(candidates 있음/없음/이미
  처리됨) — 기존 파일에 이어서 작성.

### 실행 결과
```bash
$ ./backend/gradlew -p backend test
BUILD SUCCESSFUL   # 5m 7s, 실제 Ollama generate() 호출 포함 전체 스위트 통과

$ npx eslint app/features/SearchPage.tsx
(에러 없음)
```

---

## 코드리뷰 반영 (CodeRabbit)

PR #287에 자동 코드리뷰 코멘트 4건(actionable 3 + nitpick 1)이 달렸고, 각각 다음과 같이
처리했다.

| # | 코멘트 요지 | 처리 | 근거 |
|---|---|---|---|
| 1 | `RagJobTimeoutSweeper.sweep()`: 한 job 처리 중 예외가 나면 `for` 루프 전체가 종료돼 나머지 stale job이 이번 sweep 주기에서 통째로 건너뛰어짐(Minor) | **반영함** | job 하나하나를 `try/catch`로 격리해, 하나가 실패해도 나머지는 계속 처리하도록 수정(별도 커밋) |
| 2 | `SearchPage.tsx`: `useEffect` deps가 `[awaitingAnswer]`뿐이라, 이전 검색도 PROCESSING·새 검색도 PROCESSING이면 `longWait` 타이머가 재시작되지 않음(Minor) | **반영함** | deps에 `result?.queryId` 추가 — 새 queryId마다 타이머가 리셋되도록 수정(별도 커밋) |
| 3 | `RagResponseRepository.forceFailIfProcessing()`: 스위퍼→Worker 방향 경합만 막혀있고, 반대 방향(Worker가 스위퍼보다 늦게 완료되는 경우 `completeSuccess`/`completeFailed`가 조건 없이 덮어씀)은 안 막혀 있음(Major, Heavy lift) | **반영 안 함(별도 이슈로 분리)** | 이 기존(#218 시절부터 있던) Worker 정상 완료 경로까지 손대는 리팩터링이라 규모가 커서, `#288`로 분리해 `RagResponseCommandService`/`RagFacade`/`RagJobWorker`를 대칭적으로 수정했다 |
| 4 | 순차 실행 단계에 번호 주석(`1.`, `2.`, `3.`) 추가 권장(Nitpick) | **반영 안 함** | 가치가 낮다고 판단, 스타일 변경만으로는 실질적 개선이 없음 |

## 설계 결정 요약

- **새 컬럼/마이그레이션 없음**: "얼마나 오래 PROCESSING이었는지"는 이미 있는
  `BaseEntity.createdAt`으로 충분히 판단 가능해, 진단용 `started_at` 컬럼 추가를 검토했으나
  이번 수정 범위에서는 불필요해 제외했다.
- **`RagJobWorker`를 수정하지 않고 별도 컴포넌트로 분리**: 실제 LLM 호출 로직과 타임아웃
  감시 로직을 한 클래스에 섞으면 두 관심사(정상 처리 vs 안전망)가 얽힌다. 완전히 독립된
  스케줄러로 분리해, 서로의 존재를 몰라도 동작하게 하고 경합은 DB 조건부 UPDATE로만
  방어했다.
- **fallback 문구를 새로 안 만들고 기존 것을 재사용**: LLM 실패든 타임아웃이든 사용자
  입장에선 "정상 생성은 못 했지만 관련 문서 하나는 찾아서 보여준다"는 결말이 같으므로,
  구분되는 문구/응답 구조를 일부러 만들지 않았다.
- **프론트는 데이터 흐름을 건드리지 않고 UI 텍스트만 추가**: 백엔드가 상한을 보장하는
  이상, 프론트가 별도로 타임아웃을 감지하거나 재시도할 필요가 없다.

---

## 남은 이슈 / TODO

- `stale-threshold`(90s) 기본값은 `read-timeout`(90s)에 맞춰 잡았다 — 향후 실사용 트래픽에서
  큐 적체가 잦아지면(동시 사용자 증가 등) 이 값과 `timeout-sweep-interval`(15s)을 실측
  기반으로 재조정할 필요가 있다.
- 이번 작업과 별개로, QA 중 검색은 5건이 정상적으로 잡혔는데(`search_results` 5행, 최상위
  유사도 0.64로 실제 관련 문서 일치) RAG는 candidates가 빈 것처럼 NO_CONTEXT로 즉시
  응답한 사례를 하나 발견했다(`SearchFacade`/`RagFacade.enqueue()` 연결부 의심). 이번
  수정 범위 밖이라 별도 이슈로 분리 예정.
