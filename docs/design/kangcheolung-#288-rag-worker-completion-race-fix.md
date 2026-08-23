# #288 RagJobWorker의 정상 완료가 RagJobTimeoutSweeper의 타임아웃 확정을 덮어쓰던 경합 수정

closes #288

---

## 배경

`#286`(PR #287, QA-P0-01)에서 `RagJobTimeoutSweeper`를 추가하면서, "스위퍼 → Worker" 방향의
경합만 조건부 UPDATE(`forceFailIfProcessing`, `WHERE id=? AND status='PROCESSING'`)로
막았다 — Worker가 이미 정상 완료한 job을 스위퍼가 뒤늦게 덮어쓰지 못하게 하는 안전장치였다.

`#286`(PR #287)에 대한 CodeRabbit 리뷰에서 반대 방향("Worker → 스위퍼")이 안 막혀 있다는
지적을 받았다. `RagResponseCommandService.completeSuccess()`/`completeFailed()`(Worker가
LLM 생성을 마치고 결과를 저장하는 경로)는 여전히 조건 없는 dirty-checking UPDATE라서,
스위퍼가 먼저 이 job을 FAILED로 확정한 뒤 Worker가 뒤늦게 완료 처리를 시도하면 그 결과를
그냥 덮어써버렸다.

## 문제 시나리오

```
t=0s    job 생성, PROCESSING. 큐에 밀려있어 Worker가 아직 못 집음
t=85s   Worker가 이 job을 집어 처리 시작 (Ollama 호출, 오래 걸림)
t=90s   RagJobTimeoutSweeper가 "90초 지남" 판단 → forceFailIfProcessing()으로
        FAILED + fallback 답변 커밋 성공 (이 시점 status는 아직 PROCESSING이라 조건 통과)
        → WebSocket 알림 → 프론트는 폴링/소켓을 닫고 fallback 답변 표시, 대기 종료
t=115s  Worker의 Ollama 호출이 뒤늦게 완료됨
        → completeSuccess() 호출, 조건 없이 그냥 UPDATE
        → DB의 FAILED 상태를 SUCCESS + 진짜 답변으로 무조건 덮어씀
        → RagJobWorker가 notifyAnswerReady()를 또 호출하지만, 프론트는 이미
          t=90s에 구독을 닫아서 아무도 받지 못함
```

사용자는 fallback 답변을 보고 검색을 끝냈는데, DB엔 "정상 성공"이 최종 상태로 남아 실제
사용자 경험과 기록이 어긋난다. 동시에 GPU/Worker가 1개뿐인 상황에서 이미 아무도 안 볼 답을
계산하느라 뒤에 대기 중인 다른 job의 처리가 그만큼 늦어진다 — `#286`이 해결하려던 큐 적체
문제를 스스로 악화시키는 셈이다.

## 수정 내용

핵심 아이디어는 하나다 — `#286`에서 `forceFailIfProcessing`에 썼던 **조건부 UPDATE 패턴을
Worker의 정상 완료 경로에도 대칭적으로 적용**한다.

### 1. `RagResponseRepository` — `completeSuccessIfProcessing` 추가, `forceFailIfProcessing` 재사용

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE RagResponse r SET r.status = ...SUCCESS, r.answerText = :answerText, "
     + "r.llmModelName = :llmModelName, r.inputTokenCount = :inputTokenCount, "
     + "r.outputTokenCount = :outputTokenCount, r.latencyMs = :latencyMs "
     + "WHERE r.id = :id AND r.status = ...PROCESSING")
int completeSuccessIfProcessing(...);
```

FAILED 확정 쪽은 새로 안 만들었다 — `completeFailed()`가 필요로 하는 UPDATE 모양이 스위퍼의
`forceFailIfProcessing()`과 완전히 같아서(status=FAILED + answerText + errorMessage, WHERE
status='PROCESSING') 그대로 재사용한다. 즉 지금 "PROCESSING인 job을 FAILED로 확정"하는
호출자는 `RagJobTimeoutSweeper`와 `RagResponseCommandService.completeFailed()` 둘이다.

### 2. `RagResponseCommandService` — `completeSuccess`/`completeFailed` 반환 타입 `void` → `boolean`

```java
public boolean completeSuccess(RagResponse ragResponse, OllamaGenerateResult result) {
    int updated = ragResponseRepository.completeSuccessIfProcessing(
        ragResponse.getId(), result.answerText(), result.model(),
        result.inputTokenCount(), result.outputTokenCount(), result.latencyMs()
    );
    return updated > 0;
}

public boolean completeFailed(RagResponse ragResponse, String fallbackAnswerText, String errorMessage) {
    int updated = ragResponseRepository.forceFailIfProcessing(
        ragResponse.getId(), fallbackAnswerText, errorMessage);
    return updated > 0;
}
```

엔티티를 불러와 필드를 바꾸고 dirty checking에 맡기던 방식에서, 조건부 UPDATE를 직접
호출하는 방식으로 바뀌었다. 영향받은 행 수(0/1건)를 그대로 boolean으로 반환해, 호출자가
"내가 실제로 확정시켰는지, 이미 다른 경로가 먼저 끝냈는지"를 알 수 있게 했다.

### 3. `RagFacade.processJob()` — 반환 타입 `void` → `boolean`, 경합 시 citation 저장 스킵

```java
public boolean processJob(Long jobId) {
    ...
    } catch (DocGridException e) {
        ...
        boolean completed = ragResponseCommandService.completeFailed(job, fallbackAnswer, e.getMessage());
        return completed;
    }
    ...
    boolean completed = ragResponseCommandService.completeSuccess(job, ...);
    if (!completed) {
        log.info("[RAG] job이 이미 timeout으로 종료됨(경합), 완료 결과 반영 안 함 queryId={} responseId={}", ...);
        return false;
    }
    if (!noRelevant) {
        responseCitationCommandService.saveAll(...);
    }
    return true;
}
```

`completeSuccess`/`completeFailed`가 `false`를 반환하면(스위퍼가 이미 확정함) citation
저장도 하지 않는다 — 이미 아무도 안 볼 결과에 근거 문서를 붙일 이유가 없다.
`markUnexpectedFailure()`도 동일하게 `boolean`을 반환하도록 바꿨다.

### 4. `RagJobWorker.processNext()` — 알림도 반환값에 따라 스킵

```java
try {
    if (ragFacade.processJob(job.getId())) {
        ragWebSocketController.notifyAnswerReady(userEmail, queryId);
    }
} catch (OptimisticLockingFailureException e) {
    log.warn(...);
} catch (Exception e) {
    log.error(...);
    if (ragFacade.markUnexpectedFailure(job.getId(), e.getMessage())) {
        ragWebSocketController.notifyAnswerReady(userEmail, queryId);
    }
}
```

이전엔 `processJob()`이 예외 없이 끝나기만 하면 무조건 알림을 보냈는데, 이제는 실제로
확정이 일어났을 때만 보낸다 — 스위퍼가 이미 보낸 알림 외에 Worker가 중복으로 또 보낼 이유가
없다.

### 5. `RagResponse` 엔티티 — `markSuccess`/`markFailed` 제거

완료 처리가 더 이상 엔티티 필드 변경 + dirty checking에 의존하지 않아 이 두 메서드가
production 경로에서 완전히 죽은 코드가 됐다. 제거하고, 이 메서드를 테스트 픽스처로 쓰던
`RagResponseRepositoryTest`는 `completeSuccessIfProcessing()`을 직접 호출해 "Worker가 이미
SUCCESS로 커밋한 상황"을 재현하도록 바꿨다(프로덕션이 실제로 쓰는 경로 그대로 재현하는
쪽이 더 정확하기도 하다).

## 테스트

- `RagResponseRepositoryTest`: `completeSuccessIfProcessing`에 대해 `forceFailIfProcessing`과
  대칭되는 케이스 2개 추가 — PROCESSING job은 SUCCESS로 확정(영향받은 행 1건) / **이미
  스위퍼가 FAILED로 강제 종료한 job은 덮어쓰지 않음(영향받은 행 0건)** — 후자가 이번 이슈의
  핵심 증거다.
- `RagResponseCommandServiceTest`: `completeSuccess`/`completeFailed` 각각 "조건부 UPDATE가
  반영되면 true" / "영향받은 행 0건이면 false" 케이스로 재작성.
- `RagFacadeTest`: 기존 `processJob` 성공/실패 케이스에 `completeSuccess`/`completeFailed`
  mock의 `willReturn(true)` 스텁을 추가하고, **새 경합 케이스 2개** 추가 — `completeSuccess`가
  false를 반환하면 citation 저장을 건너뛰고 `processJob`도 false를 반환 / `completeFailed`가
  false를 반환해도 동일.
- `RagJobWorkerTest`: 기존 성공 케이스에 `processJob`/`markUnexpectedFailure`의
  `willReturn(true)` 스텁 추가, **새 케이스** 추가 — `processJob`이 false를 반환하면
  `notifyAnswerReady`를 호출하지 않는다.

### 실행 결과

```bash
$ ./backend/gradlew -p backend test
BUILD SUCCESSFUL in 4m 56s
```

전체 스위트(단위 + integration, 실제 Ollama 호출 포함) 통과. 흥미롭게도 실제 integration
테스트 실행 중에 이번에 고친 경합이 **실사용 시나리오로 실제 발생**해서 로그로 확인됐다:

```
[RAG] job이 이미 timeout으로 종료됨(경합), 완료 결과 반영 안 함 queryId=41 responseId=41
[RAG] job이 이미 timeout으로 종료됨(경합), 완료 결과 반영 안 함 queryId=51 responseId=51
```

`RagJobTimeoutSweeper`가 실제로 먼저 확정한 job을 Worker가 뒤늦게 완료 시도했고, 새 조건부
UPDATE가 그걸 정확히 거부했다는 뜻이다 — 목(mock)이 아니라 실제 스케줄러 두 개가 동시에
돌아가는 환경에서 이 수정이 의도대로 동작함을 우연히 실증했다.

## 설계 결정 요약

- **엔티티 mutation + dirty checking → 조건부 벌크 UPDATE로 완전히 전환**: `#286`에서는
  스위퍼 쪽만 조건부 UPDATE였고 Worker 쪽은 그대로였는데, 이번에 Worker 쪽도 동일한 방식으로
  통일해 "두 스케줄러 중 누가 먼저 끝내든, 나중에 도착한 쪽은 반드시 무시된다"는 대칭적
  보장을 만들었다.
- **`forceFailIfProcessing`을 두 호출자가 공유**: FAILED 확정 SQL 모양이 동일해서 새 메서드를
  안 만들고 재사용했다 — 스위퍼의 타임아웃 fallback과 Worker의 LLM-실패 fallback이 이미
  화면상 구분 없는 결과이듯, DB 갱신 메커니즘도 구분할 이유가 없었다.
- **`markSuccess`/`markFailed` 제거**: 조건부 UPDATE로 전환되며 production에서 완전히
  죽은 코드가 돼 제거했다. 테스트 픽스처도 실제 프로덕션 경로(`completeSuccessIfProcessing`)를
  그대로 쓰도록 맞췄다.

## 관련 이슈

`#286`(PR #287) — 이번 수정이 보완하는 원본 타임아웃 스위퍼 기능.
