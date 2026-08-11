# Issue #151 RAGOps Dashboard 이벤트 기반 실시간 갱신 설계

closes #151

## 1. 배경과 목적

이슈#134(집계 API)·이슈#137(WebSocket push 인프라)·이슈#148(관리자 재처리)까지 나오면서
대시보드는 "조회하면 최신 값을 보여줄 수 있고", "관리자가 재처리 버튼을 누르면 그 결과를
바로 push 받을 수" 있게 됐다. 하지만 대시보드가 진짜로 실시간이 되려면, 관리자가 아무 버튼도
안 눌러도 — Worker들이 백그라운드에서 계속 Job을 Claim하고, 처리를 끝내고, 실패시키는 그
순간마다 — 화면이 저절로 갱신돼야 한다. 지금까지는 그 트리거가 없었다.

이 트리거는 A담당자가 소유한 Worker 파이프라인(`EmbeddingJobClaimService`,
`DocumentIndexingCompletionService`, `DocumentIndexingFailureService`) 안에서만 일어난다.
B가 이 세 Service의 상태 전환 로직 자체를 만들거나 건드리는 게 아니라, "상태가 바뀌었다"는
사실을 대시보드 쪽에 알려주는 아주 얇은 신호선 하나를 이 세 지점에 심는 작업이다.

동시에 순수하게 "이벤트가 발생할 때마다 즉시 집계를 재계산해서 push"하는 방식은 안 된다.
Worker는 여러 스레드가 동시에 도는 구조라, 예를 들어 전체 재처리(이슈#148의 retry-all)로
27개 Job이 짧은 시간에 한꺼번에 상태를 바꾸면 이벤트도 27번 발생한다. 이벤트마다 매번 집계
쿼리(`DashboardQueryService.getSummary()`, 약 9개 쿼리)를 새로 돌리면 27 × 9 ≈ 243개 쿼리가
찰나의 순간에 몰려서 DB에 불필요한 부하를 준다. 그래서 "상태가 바뀌었다"는 사실만 짧게
모아뒀다가, 일정 주기(debounce)마다 한 번씩만 실제로 재계산·push하는 장치가 같이 필요하다.

### 1.1 성공 기준

- Worker가 Job을 Claim/완료/실패시키는 시점마다 대시보드 집계가 자동으로 다시 계산되어
  `/topic/dashboard` 구독자에게 push된다 (관리자가 새로고침하거나 버튼을 누르지 않아도).
- 짧은 시간에 여러 상태 전이가 몰려도(burst) 집계 쿼리는 debounce 주기당 최대 1회 수준으로
  억제된다 — 이벤트 개수만큼 DB 부하가 늘어나지 않는다.
- 상태 전이를 발생시킨 Transaction이 실제로 커밋된 경우에만 push로 이어진다. 롤백되면 push가
  발생하지 않는다.
- A담당자가 담당하는 세 Service는 상태 전환 로직을 전혀 바꾸지 않는다 — 각 지점에 이벤트 발행
  한 줄만 추가한다.

### 1.2 참고 — 상태·훅·필드 이름 한글 설명

리뷰 과정에서 정리한 참고 표. 아래 표들은 이 이슈를 처음 보는 사람이 "어떤 훅이 어떤 상태
전이를 의미하고 대시보드의 어떤 숫자에 영향을 주는지"를 한눈에 잡기 위한 것이다.

**Embedding Job 상태 값**

| 상태 값 | 한글 설명 |
|---|---|
| `PENDING` | 대기 중 — Queue에 있고 아직 아무 Worker도 안 집어감 |
| `PROCESSING` | 처리 중 — 어떤 Worker가 Claim해서 지금 임베딩 작업 중 |
| `INDEXED` | 완료됨 — 임베딩까지 끝나서 검색 가능해짐 |
| `FAILED` | 실패함 — 재시도 한도를 넘겨 더 이상 자동 재시도되지 않음 |

**이번 이슈에서 심은 훅 이름**

| 훅 | 소속 Service | 한글로 풀면 |
|---|---|---|
| Claim | `EmbeddingJobClaimService` | "내가(Worker가) 이 Job을 맡을게" |
| Completion | `DocumentIndexingCompletionService` | "이 Job 처리 다 끝냈어" |
| Failure | `DocumentIndexingFailureService` | "이 Job 처리하다 실패했어" |

**대시보드 필드 이름**

| 필드 | 한글 설명 |
|---|---|
| `jobs.pending` | PENDING 상태인 Job 수 |
| `jobs.processing` | PROCESSING 상태인 Job 수 |
| `jobs.failed` | FAILED 상태인 Job 수 |
| `jobs.avgProcessMs` | 평균 임베딩 처리 시간(ms), Queue 대기 시간 제외 |
| `documents.searchable` | 검색 가능(INDEXED) 문서 수 |
| `documents.pendingIndex` | 아직 인덱싱이 안 끝난(대기·처리 중) 문서 수 |

**훅 → 상태 전이 → 영향받는 대시보드 필드**

| 훅 | 상태 전이 | 영향받는 필드 |
|---|---|---|
| Claim | `PENDING` → `PROCESSING` | `jobs.pending` ↓, `jobs.processing` ↑ |
| Completion | `PROCESSING` → `INDEXED` | `jobs.processing` ↓, `jobs.avgProcessMs` 재계산, `documents.searchable` ↑, `documents.pendingIndex` ↓ |
| Failure (재시도 예약) | `PROCESSING` → `PENDING` | `jobs.processing` ↓, `jobs.pending` ↑ |
| Failure (최종 실패) | `PROCESSING` → `FAILED` | `jobs.processing` ↓, `jobs.failed` ↑, `documents.pendingIndex` ↓ |

세 훅 모두 "Job이 지금 PROCESSING 상태에서 벗어나거나 PROCESSING 상태로 들어간다"는 공통점이
있다 — 즉 셋 다 `jobs.processing` 카드에 영향을 준다. 그래서 이벤트 자체는 "무엇이 바뀌었는지"를
싣지 않고 `jobId`만 담는 마커로 설계했다(4.1절 참고) — 구독 측이 어차피 매번 전체 집계를 다시
계산하므로 세부 상태를 실어봐야 아무도 안 쓴다.

## 2. 범위

### 2.1 포함

- `EmbeddingJobStatusChangedEvent` — 상태 전이 마커 이벤트
- `EmbeddingJobClaimService` / `DocumentIndexingCompletionService` /
  `DocumentIndexingFailureService`(전부 A 소유) 세 지점에 이벤트 발행 한 줄씩 추가
- `DashboardUpdateFlag` — dirty 플래그 (스레드 안전)
- `EmbeddingJobStatusChangedEventListener` — `AFTER_COMMIT` 구독자
- `DashboardPushScheduler` — debounce 주기 스케줄러
- `DashboardSchedulingConfig` — 대시보드 전용 무조건부 스케줄링 활성화
- 단위 테스트 4종 + PostgreSQL 통합 테스트 2종

### 2.2 제외

- WebSocket push 인프라 자체 — 이슈#137에서 이미 완성 (`DashboardWebSocketController` 재사용)
- 집계 쿼리 로직 자체 — 이슈#134에서 이미 완성 (`DashboardQueryService` 재사용)
- Embedding Job 상태 전환 로직 자체 — A담당자 소유, 이번 이슈에서 전혀 건드리지 않음
- `embedding` 도메인이 `dashboard` 패키지를 참조하지 못하게 강제하는 ArchUnit 규칙 — 필요성은
  논의했지만 이번 범위에서는 수동 리뷰로 충분하다고 판단해 제외 (나중에 필요하면 별도 이슈)

## 3. 아키텍처 개요

```text
[Worker Thread]                                    [Scheduler Thread — 300ms마다]
     │
     │ ① Claim/Completion/Failure 로직 실행
     │    (A 소유, 이번 이슈에서 안 건드림)
     │
     │ ② applicationEventPublisher.publishEvent(
     │        new EmbeddingJobStatusChangedEvent(jobId))
     │
     ▼
[Transaction 커밋]
     │
     │ ③ AFTER_COMMIT — 커밋 성공한 경우에만 호출됨
     ▼
[EmbeddingJobStatusChangedEventListener]
     │
     │ ④ dashboardUpdateFlag.markDirty()   ← DB 조회도 push도 없음, 그냥 boolean 하나 true로
     ▼
[DashboardUpdateFlag (dirty = true)]  ◄───────────────┐
                                                        │ ⑤ consumeIfDirty() 폴링
                                                        │    (true였으면 false로 원자적 리셋)
                                              [DashboardPushScheduler.pushIfDirty()]
                                                        │
                                                        │ ⑥ dirty였을 때만:
                                                        │    getSummary() 재계산 + WebSocket push
                                                        ▼
                                              [/topic/dashboard 구독자 전원에게 push]
```

핵심 설계 결정 세 가지가 이 흐름에 녹아 있다 (근거는 5장에서 상세히 다룸):

1. **`AFTER_COMMIT`** — 확정되지 않은(롤백될 수도 있는) 상태 변화로 대시보드가 갱신되면 안 된다.
2. **Flag + Scheduler 분리(debounce)** — burst 상황에서 이벤트 개수만큼 DB 부하가 늘어나면 안 된다.
3. **전용 `@EnableScheduling`** — 대시보드 push는 A의 자동 Worker On/Off 설정과 무관하게 항상
   동작해야 한다.

## 4. 구현 상세

읽는 순서는 리뷰 때와 동일하게 설정 파일 → 발행 지점(Worker 훅) → 구독·debounce 부품 →
스케줄링 활성화 순으로 정리한다.

### 4.1 `build.gradle` — 테스트 의존성 추가

```diff
 testImplementation 'org.postgresql:postgresql'
+testImplementation 'org.awaitility:awaitility'
```

6.5절의 burst debounce 통합 테스트가 "비동기로 도는 스케줄러가 언젠가 조건을 만족할 때까지"
기다려야 하는데, `Thread.sleep`으로 임의 시간을 잡으면 느리거나 flaky해진다. Awaitility의
`await().atMost(...).untilAsserted(...)`로 "최대 N초까지, 조건 만족하면 즉시 통과"하도록
바꿨다.

### 4.2 `application.yml` — 스케줄러 풀 크기와 debounce 주기

```diff
   task:
     scheduling:
       pool:
-        # DB Claim 지연이 Worker Heartbeat와 Lease 복구 실행을 막지 않도록 세 작업을 분리한다.
-        size: 3
+        # DB Claim 지연이 Worker Heartbeat·Lease 복구 실행을 막지 않도록, Dashboard debounce push까지
+        # 포함해 네 작업을 분리한다.
+        size: 4
+
+dashboard:
+  push:
+    # 짧은 시간에 몰리는 Job 상태 전이 이벤트를 이 주기로 모아서 한 번만 집계·push한다(debounce).
+    debounce-interval-ms: ${DASHBOARD_PUSH_DEBOUNCE_INTERVAL_MS:300}
```

- `spring.task.scheduling.pool.size`는 스프링이 `@Scheduled` 메서드들을 실행할 스레드 풀
  크기다. 기존에 Worker의 Heartbeat·Lease 복구가 스레드 2개를 쓰고 있었고(주석상 "세 작업"이라
  3으로 잡혀 있었음), 이번에 `DashboardPushScheduler.pushIfDirty()`가 4번째 `@Scheduled`
  메서드로 추가되므로 풀 크기를 3→4로 늘렸다. 늘리지 않으면 대시보드 push 스케줄러가 Worker의
  다른 스케줄과 스레드를 경합하면서 실행이 밀릴 수 있다.
- `debounce-interval-ms`는 환경변수 `DASHBOARD_PUSH_DEBOUNCE_INTERVAL_MS`로 오버라이드 가능한
  기본값 300(ms)이다. 이 값이 `DashboardPushScheduler`의 `@Scheduled(fixedDelayString = ...)`에
  그대로 꽂힌다(4.8절).

### 4.3 `EmbeddingJobStatusChangedEvent` — 상태 전이 마커 이벤트

```java
package com.opensource.docgrid.domain.embedding.event;

public record EmbeddingJobStatusChangedEvent(Long jobId) {
}
```

`dashboard` 패키지가 아니라 **`embedding` 패키지**에 만들었다. 프로젝트의 도메인 간 의존은
단방향이어야 하는데(`java-style.md`), 대시보드는 원래도 임베딩 상태를 읽어야 하는 입장이라
`dashboard → embedding` 방향 의존은 이미 자연스럽다. 반대로 이벤트를 `dashboard` 패키지에
두면 A가 소유한 `embedding` 도메인의 Service들이 이벤트를 발행하기 위해 `dashboard` 패키지를
import해야 하고, 이는 `embedding → dashboard`라는 역방향 의존을 만들어 순환 참조 위험을
발생시킨다. 그래서 이벤트는 "발행하는 쪽(embedding)"의 도메인에 두고, "구독하는
쪽(dashboard)"이 그 패키지를 바라보는 방향으로만 의존이 흐르게 했다.

필드는 `jobId` 하나뿐이고 어떤 상태에서 어떤 상태로 바뀌었는지는 담지 않는다. 구독 측
(`EmbeddingJobStatusChangedEventListener`)은 이벤트를 받으면 세부 내용과 무관하게 항상 "전체
집계를 다시 계산해야 한다"는 플래그만 세우기 때문에, 페이로드를 더 풍부하게 만들어도 실제로
쓰이는 곳이 없다.

### 4.4 Worker 훅 3곳 — 이벤트 발행 지점

A담당자가 소유한 세 Service 각각에 `ApplicationEventPublisher` 필드를 추가하고, 상태 전환이
이미 끝난 직후 지점에 발행 한 줄을 심었다. **상태 전환 로직 자체는 한 글자도 건드리지 않았다.**

**`EmbeddingJobClaimService` — Claim (PENDING → PROCESSING)**

```diff
     private final Clock clock;
+    private final ApplicationEventPublisher applicationEventPublisher;
```
```diff
-        // 4. Transaction 안에서 LAZY 연관 식별자를 읽어 Claim 결과 DTO를 완성한다.
+        // 4. 대시보드가 최신 집계를 다시 계산하도록 상태 전이를 알린다. AFTER_COMMIT 구독자만
+        //    반응하므로 이 Transaction이 실제로 커밋된 뒤에만 push로 이어진다.
+        applicationEventPublisher.publishEvent(new EmbeddingJobStatusChangedEvent(embeddingJob.getId()));
+
+        // 5. Transaction 안에서 LAZY 연관 식별자를 읽어 Claim 결과 DTO를 완성한다.
         return embeddingJobConverter.toClaimedResponse(embeddingJob);
```

LOCKED `IndexingEvent`를 저장한 직후, 응답 DTO를 만들기 전에 발행한다. 이 시점이면 이미
`embeddingJob`의 상태 변경(`save`에 준하는 dirty checking 대상)이 영속성 컨텍스트에 반영된
뒤라, 이후 Transaction 커밋이 실제 상태 변경을 포함한다.

**`DocumentIndexingCompletionService` — Completion (PROCESSING → INDEXED)**

```diff
     private final Clock clock;
+    private final ApplicationEventPublisher applicationEventPublisher;
```
```diff
+        // 6. 대시보드가 최신 집계를 다시 계산하도록 상태 전이를 알린다. AFTER_COMMIT 구독자만
+        //    반응하므로 이 Transaction이 실제로 커밋된 뒤에만 push로 이어진다.
+        applicationEventPublisher.publishEvent(new EmbeddingJobStatusChangedEvent(embeddingJob.getId()));
+
         log.info(
             "문서 인덱싱 완료: jobId={}, attemptId={}, documentId={}, versionId={}, "
```

`transitionAndRecordEvent(...)` 호출 직후, 완료 로그를 남기기 직전에 발행한다. 기존 메서드의
스텝 번호(1~5)를 그대로 유지하고 이벤트 발행을 6번으로 이어 붙였다 — 기존 주석 체계를
건드리지 않기 위함이다.

**`DocumentIndexingFailureService` — Failure (재시도 예약 or 최종 실패)**

이 Service는 `@RequiredArgsConstructor`가 아니라 **수동으로 작성된 생성자 2개**를 가지고
있어서(운영 경로용 1개, 테스트/내부 경로용 1개), 둘 다 `ApplicationEventPublisher` 파라미터를
추가해야 했다.

```diff
     private final Clock clock;
+    private final ApplicationEventPublisher applicationEventPublisher;
```
```diff
     public DocumentIndexingFailureService(
         ...
-        Clock clock
+        Clock clock,
+        ApplicationEventPublisher applicationEventPublisher
     ) {
         ...
         this.clock = clock;
+        this.applicationEventPublisher = applicationEventPublisher;
     }
```
```diff
     public DocumentIndexingFailureService(   // 테스트/내부 경로용 보조 생성자
         ...
-        Clock clock
+        Clock clock,
+        ApplicationEventPublisher applicationEventPublisher
     ) {
         this(
             ...
-            clock
+            clock,
+            applicationEventPublisher
         );
     }
```
```diff
+        // 4. 대시보드가 최신 집계를 다시 계산하도록 상태 전이를 알린다. transition()이 재시도 예약
+        //    (PENDING)과 최종 실패(FAILED) 중 어느 쪽으로 끝났든 embeddingJob은 같은 영속 인스턴스라
+        //    최종 상태를 그대로 반영한다. AFTER_COMMIT 구독자만 반응하므로 이 Transaction이 실제로
+        //    커밋된 뒤에만 push로 이어진다.
+        applicationEventPublisher.publishEvent(new EmbeddingJobStatusChangedEvent(embeddingJob.getId()));
```

`fail()`이 직접 상태를 판단하지 않는다 — 실제 PENDING(재시도) vs FAILED(최종 실패) 분기는
별도의 `IndexingFailureTransitionService.transition()`이 내부에서 `scheduleRetry()` 또는
`terminateFailure()`로 갈라 처리한다. `fail()` 입장에서는 `transition()` 호출이 끝난 시점에
`embeddingJob`(JPA 영속 인스턴스)이 이미 최종 상태로 갱신돼 있으므로, 어느 분기를 탔는지 여기서
분기할 필요 없이 발행 한 줄만 두면 된다 — "무엇으로 바뀌었는지"는 이벤트가 담지 않기 때문에
(4.3절) 이 지점에서 신경 쓸 필요가 없다.

### 4.5 `DashboardUpdateFlag` — 스레드 안전 dirty 플래그

```java
@Component
public class DashboardUpdateFlag {

    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public void markDirty() {
        dirty.set(true);
    }

    public boolean consumeIfDirty() {
        return dirty.compareAndSet(true, false);
    }
}
```

"대시보드 집계가 최신이 아니다"라는 사실 하나만 기억하는 부품. `markDirty()`는 여러 Worker
스레드가 동시에 호출해도 안전하고, DB 조회나 push 같은 무거운 작업을 전혀 하지 않는다. 몇 번을
호출해도 결과는 `true` 하나뿐이라(coalesce), burst 상황에서 이벤트가 27번 들어와도 플래그
자체는 "바뀐 게 있다/없다" 두 가지 상태만 가진다.

`consumeIfDirty()`가 `compareAndSet(true, false)`를 쓰는 이유는 "확인"과 "초기화"를 한 번의
원자 연산으로 묶기 위해서다. 만약 `if (dirty.get()) { dirty.set(false); ... }`처럼 두 단계로
나누면, 그 사이(get 직후·set 직전) 다른 스레드가 `markDirty()`를 호출한 경우 그 신호가 다음
`set(false)`에 덮여 사라질 수 있다. `compareAndSet`은 "지금 값이 true면 원자적으로 false로
바꾸고 성공 여부(=원래 true였는지)를 반환"하므로 이런 손실이 생기지 않는다.

실제 흐름:

```text
[시작] dirty = false

Worker가 job A를 claim → 리스너가 markDirty() 호출 → dirty = true
... 0~300ms 사이 ...
스케줄러가 300ms마다 도는 시점 → consumeIfDirty() 호출
  → dirty가 true였으니 true 반환, 동시에 dirty = false로 리셋
  → 스케줄러: "바뀐 게 있었네" → getSummary() 계산 → WebSocket push

... 다음 300ms, 아무도 markDirty()를 안 부른 경우 ...
스케줄러가 다시 consumeIfDirty() 호출 → dirty가 false였으니 false 반환
  → 스케줄러: "바뀐 거 없네" → 아무것도 안 하고 다음 주기까지 대기
```

### 4.6 `EmbeddingJobStatusChangedEventListener` — AFTER_COMMIT 구독자

```java
@Component
@RequiredArgsConstructor
public class EmbeddingJobStatusChangedEventListener {

    private final DashboardUpdateFlag dashboardUpdateFlag;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmbeddingJobStatusChanged(EmbeddingJobStatusChangedEvent event) {
        dashboardUpdateFlag.markDirty();
    }
}
```

`phase = AFTER_COMMIT`이라 이벤트를 발행한 Transaction이 **실제로 커밋된 뒤에만** 호출된다.
롤백되면 이 메서드 자체가 아예 실행되지 않는다 — 즉 "확정되지 않은 상태 변화로 대시보드가
갱신되는 일"이 구조적으로 불가능하다(스프링 기본값 `fallbackExecution=false`라 활성 트랜잭션이
없는 채로 발행되면 그 이벤트는 조용히 버려진다는 점도 6.5절 통합 테스트 설계에 영향을 줬다).

이 클래스는 대시보드를 직접 갱신하지 않는다. `DashboardUpdateFlag.markDirty()`만 호출하고
끝난다 — DB 조회나 push 같은 무거운 작업은 스케줄러가 나중에 debounce 주기로 모아서 한다.

### 4.7 `DashboardPushScheduler` — debounce 스케줄러

```java
@Component
@RequiredArgsConstructor
public class DashboardPushScheduler {

    private final DashboardUpdateFlag dashboardUpdateFlag;
    private final DashboardQueryService dashboardQueryService;
    private final DashboardWebSocketController dashboardWebSocketController;

    @Scheduled(fixedDelayString = "${dashboard.push.debounce-interval-ms}")
    public void pushIfDirty() {
        if (dashboardUpdateFlag.consumeIfDirty()) {
            dashboardWebSocketController.sendDashboardUpdate(dashboardQueryService.getSummary());
        }
    }
}
```

이 클래스는 새 로직을 거의 만들지 않는다 — 이미 있는 세 부품을 "언제 조합해서 실행할지"만
정한다. "바뀌었는지 확인"은 `DashboardUpdateFlag`, "최신 집계 계산"은
`DashboardQueryService`(이슈#134), "WebSocket 전송"은 `DashboardWebSocketController`(이슈#137)가
이미 만들어 둔 것을 그대로 가져다 쓴다.

`fixedDelayString`을 쓴 이유(vs `fixedRate`): `fixedDelay`는 "이전 실행이 끝난 시점부터"
설정된 간격 뒤에 다음 실행이 잡힌다. `fixedRate`처럼 "시작 시점 기준 고정 주기"로 잡으면, 이
메서드가 드물게 오래 걸릴 경우(예: DB가 순간적으로 느려짐) 다음 실행과 겹치거나 밀려서 쌓일 수
있다. `fixedDelay`를 쓰면 항상 이전 실행이 완전히 끝난 뒤 간격을 재기 시작하므로 이런 겹침이
구조적으로 없다.

### 4.8 `DashboardSchedulingConfig` — 전용 스케줄링 활성화

```java
@Configuration
@EnableScheduling
public class DashboardSchedulingConfig {
}
```

기존에 `@EnableScheduling`은 `WorkerSchedulingConfig`에 이미 있었지만, 이 설정은
`@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")`로
막혀 있고 `indexing.worker.enabled`의 기본값은 `false`다. 즉 자동 Worker 기능을 끈 기본
상태에서는 스프링 스케줄링 자체가 앱 전체에서 꺼져 있어서, `DashboardPushScheduler`의
`@Scheduled`가 있어도 조용히 아무 일도 안 일어난다(예외도 로그도 없음).

대시보드 push는 자동 Worker On/Off와 무관하게(예: 관리자가 이슈#148의 수동 재처리만 써도)
항상 동작해야 하므로, 조건 없는 별도 `@Configuration` 클래스를 새로 만들었다.
`@EnableScheduling`을 여러 설정 클래스에서 선언해도 스프링이 안전하게(중복 등록 없이) 처리한다.

## 5. 설계 결정 요약

| 결정 | 이유 |
|---|---|
| 이벤트를 `dashboard`가 아닌 `embedding` 패키지에 둠 | 도메인 간 단방향 의존(`dashboard → embedding`) 유지, A 소유 코드가 B 패키지를 import하지 않게 하기 위함 |
| 이벤트에 상태 세부 정보를 안 담고 `jobId`만 | 구독 측이 항상 전체 집계를 재계산하므로 세부 상태를 실어도 안 쓰임 |
| `AFTER_COMMIT`으로 구독 | 롤백된 상태 변화로 대시보드가 갱신되는 일을 구조적으로 차단 |
| Flag + Scheduler로 분리(debounce) | burst 상황에서 이벤트 개수만큼 DB 쿼리가 느는 것을 방지 (27건 → ~243쿼리를 ~9쿼리로 억제) |
| `compareAndSet`으로 확인+초기화를 원자화 | 스케줄러가 확인하는 순간과 새 이벤트가 겹쳐도 신호 유실 없음 |
| `fixedDelay` (not `fixedRate`) | 실행이 드물게 오래 걸려도 다음 실행과 겹치지 않음 |
| 전용 `DashboardSchedulingConfig` 추가 | 대시보드 push가 `indexing.worker.enabled` 설정과 무관하게 항상 동작해야 함 |

## 6. 테스트 설계

### 6.1 `DashboardUpdateFlagTest` — 단위 테스트, 순수 객체 (Mock 없음)

`AtomicBoolean` 기반 로직이라 스프링 컨텍스트나 Mock 없이 `new DashboardUpdateFlag()`로 직접
검증한다.

| 테스트 | 시나리오 | 검증 |
|---|---|---|
| `consumeIfDirty_returnsTrueOnceThenFalse_afterMarkDirty` | `markDirty()` 1번 호출 후 `consumeIfDirty()`를 연속 2번 호출 | 1번째는 `true`(dirty였음), 2번째는 `false`(이미 소비되어 리셋됨) — "확인하면서 동시에 리셋"이 실제로 원자적으로 동작함을 증명 |
| `consumeIfDirty_coalescesMultipleMarkDirtyCalls` | `markDirty()`를 연속 3번 호출 후 `consumeIfDirty()`를 연속 2번 호출 | 여러 번 세워도 결과는 1번째 `true`, 2번째 `false` — burst로 이벤트가 몰려도 플래그는 하나로 합쳐짐(coalesce)을 증명 |
| `consumeIfDirty_returnsFalse_whenNeverMarkedDirty` | `markDirty()`를 한 번도 안 부른 새 인스턴스에서 바로 `consumeIfDirty()` | `false` — 앱 시작 직후 기본 상태가 "최신"임을 증명 |

### 6.2 `EmbeddingJobStatusChangedEventListenerTest` — 단위 테스트 (Mockito)

리스너가 "플래그만 세우고 그 이상 아무것도 안 한다"는 걸 증명하는 게 목적이라, 검증은
상호작용 확인 위주다.

| 테스트 | 시나리오 | 검증 |
|---|---|---|
| `onEmbeddingJobStatusChanged_marksFlagDirty` | `EmbeddingJobStatusChangedEvent(42L)`를 리스너에 직접 전달 | `dashboardUpdateFlag.markDirty()`가 정확히 1번 호출됨(`then(...).should()`), 그리고 `shouldHaveNoMoreInteractions()`로 그 외의 어떤 메서드도 호출되지 않았음을 함께 확인 — "무거운 작업 없이 플래그만 세운다"는 설계를 상호작용 개수로 증명 |

여기서는 `@TransactionalEventListener(AFTER_COMMIT)`이 실제로 커밋 이후에만 불리는지는
검증하지 않는다(단위 테스트는 메서드를 직접 호출하므로 트랜잭션 프록시를 통하지 않음) — 이
부분은 6.5절 통합 테스트가 담당한다.

### 6.3 `DashboardPushSchedulerTest` — 단위 테스트 (Mockito)

| 테스트 | 시나리오 | 검증 |
|---|---|---|
| `pushIfDirty_computesAndPushes_whenFlagWasDirty` | `dashboardUpdateFlag.consumeIfDirty()`가 `true`를 반환하도록 stub | `dashboardWebSocketController.sendDashboardUpdate(summary)`가 `dashboardQueryService.getSummary()`가 반환한 값과 함께 정확히 호출됨 |
| `pushIfDirty_doesNothing_whenFlagWasNotDirty` | `consumeIfDirty()`가 `false`를 반환하도록 stub | `dashboardQueryService`와 `dashboardWebSocketController` 양쪽 모두 `shouldHaveNoInteractions()` — dirty가 아니면 집계 쿼리 자체가 안 나간다는 것(=debounce의 핵심)을 증명 |

두 번째 테스트가 이 이슈의 성능 목표(불필요한 DB 조회 억제)를 가장 직접적으로 보여주는
단위 테스트다 — dirty가 아닐 때 `getSummary()`가 아예 호출되지 않는다는 걸 상호작용 부재로
증명한다.

### 6.4 기존 3개 Service 테스트 — 생성자 시그니처 보정만 (신규 검증 로직 없음)

`EmbeddingJobClaimServiceTest`, `DocumentIndexingCompletionServiceTest`,
`DocumentIndexingFailureServiceTest`는 4.4절에서 세 Service의 생성자에
`ApplicationEventPublisher`가 추가되면서 컴파일이 깨졌던 걸 고치기 위한 변경이다. 각 테스트에
`@Mock private ApplicationEventPublisher applicationEventPublisher;`를 추가하고 생성자 호출에
그대로 전달했다. 새로운 `@Test` 케이스는 추가하지 않았다 — 이벤트 발행이 "성공했는지"를 이
Service들의 단위 테스트에서 검증하지 않는 이유는, Mock으로 만든 `ApplicationEventPublisher`는
실제 스프링 트랜잭션 이벤트 발행/구독 메커니즘을 전혀 타지 않으므로 검증해도 의미가 없기
때문이다(실제 발행→AFTER_COMMIT 흐름은 6.5절 통합 테스트가 담당).

### 6.5 `DashboardPushDebounceIntegrationTest` — burst debounce 통합 테스트

`@Tag("integration")`, 격리된 스키마(`docgrid_dashboard_push_debounce_test`) 사용. debounce
주기는 `500ms`로 늘려서(27개 커밋이 한 주기 안에 다 들어오도록) 오버라이드.

**목적**: 5장의 핵심 주장 — "burst 상황에서도 집계 쿼리는 이벤트 개수가 아니라 debounce
주기당 최대 1회 수준으로 억제된다" — 는 Hibernate `Statistics`로 실제 쿼리 개수를 세지 않으면
증명할 수 없다. 로그를 눈으로 세는 방식은 신뢰할 수 없어서, `entityManagerFactory`에서
`SessionFactory.getStatistics()`를 꺼내 `getPrepareStatementCount()`로 정확한 개수를 잰다.

| 테스트 | 시나리오 | 검증 |
|---|---|---|
| `burstEvents_areCoalescedIntoSingleAggregationQuery` | `EmbeddingJobStatusChangedEvent`를 `jobId=1~27`로 27번 발행, 각각을 `PROPAGATION_REQUIRES_NEW` `TransactionTemplate`로 **독립적으로 커밋**시켜 실제 burst(동시다발 상태 전이)를 재현 | `Awaitility.await().atMost(3초).untilAsserted(...)`로 폴링하며 `statistics.getPrepareStatementCount()`가 `isPositive()`(최소 1회는 집계가 돎)이면서 동시에 `isLessThan(20)`(27 × 9 ≈ 243이 아니라 1회 집계분인 9개 안팎에서 멈춤)인지 확인 |

각 이벤트를 `REQUIRES_NEW`로 독립 커밋시키는 이유: 바깥의 테스트 메서드 트랜잭션에 그냥
참여시키면(기본 `REQUIRED`) 27개 발행이 테스트 메서드가 끝날 때 딱 한 번의 커밋으로 묶여버려서,
"짧은 시간에 여러 번 독립적으로 커밋되는" burst 상황 자체가 재현되지 않는다. 진짜 27번의 개별
커밋을 만들어야 `AFTER_COMMIT` 리스너도 27번 호출되고, 그 27번의 `markDirty()` 호출이 정말로
스케줄러 debounce에 의해 하나로 뭉쳐지는지를 검증할 수 있다.

`statistics.clear()`를 `@BeforeEach`뿐 아니라 burst 트리거 직전에도 한 번 더 호출해서, 그
사이의 준비 작업(TRUNCATE 등)에서 발생한 쿼리가 카운트에 섞이지 않도록 했다.

### 6.6 `EmbeddingJobStatusChangedAfterCommitIntegrationTest` — AFTER_COMMIT 실동작 검증

`@Tag("integration")`, `@SpringBootTest(webEnvironment = RANDOM_PORT)`, 실제 STOMP Client로
`/topic/dashboard`를 구독해 이슈#137에서 확립한 패턴(`SimpUserRegistry` 폴링으로 구독 등록
확인 — `SimpleBrokerMessageHandler`는 STOMP Receipt를 지원하지 않아 `addReceiptTask()`가 절대
안 불림)을 재사용한다. debounce 주기는 `200ms`로 단축.

**목적**: 4.6절의 핵심 주장 — "커밋되면 push되고, 롤백되면 push되지 않는다" — 를 이론이 아니라
실제 Transaction 경계로 끝까지 관통 검증.

| 테스트 | 시나리오 | 검증 |
|---|---|---|
| `push_arrives_afterEventPublishingTransactionCommits` | STOMP로 `/topic/dashboard` 구독 후, `REQUIRES_NEW` 트랜잭션 안에서 이벤트를 발행하고 정상 커밋 | `received.poll(5초)`로 받은 `DashboardSummaryResponse`가 `null`이 아님 — 실제로 push가 도착함 |
| `push_doesNotArrive_whenEventPublishingTransactionRollsBack` | STOMP로 구독 후, `REQUIRES_NEW` 트랜잭션 안에서 이벤트를 발행하지만 `status.setRollbackOnly()`로 명시적 롤백 | debounce 주기(200ms)보다 확실히 긴 `800ms`(`NO_PUSH_WAIT_MILLIS`)를 대기한 뒤 `received`가 비어 있음(`isEmpty()`) — 롤백되면 `AFTER_COMMIT` 리스너 자체가 안 불려서 push가 전혀 발생하지 않음을 증명 |

두 번째 테스트의 대기 시간(800ms)이 debounce 주기(200ms)의 4배인 이유: "push가 없다"는 부재
증명은 최소 한두 debounce 주기를 그냥 흘려보내도 여전히 아무 일도 없어야 신뢰할 수 있다.
200ms만 기다리면 스케줄러가 아직 한 번도 안 돌았을 수도 있어 "안 왔다"가 우연일 수 있지만,
800ms(=4주기 이상)를 기다려도 안 왔다면 debounce 타이밍 문제가 아니라 정말 안 온 것이라고
판단할 수 있다.

`SimpUserRegistry` 폴링(`awaitSubscriptionRegistered()`)으로 구독이 서버에 실제 등록됐는지
먼저 확인한 뒤에 이벤트를 발행한다 — 그렇지 않으면 push가 구독 등록 전에 나가버려서 클라이언트가
못 받는 타이밍 문제가 생길 수 있다.

## 7. 커밋 분할

1. `docs: #151 RAGOps Dashboard 이벤트 기반 실시간 갱신 설계 문서 추가`
2. `feat: #151 Embedding Job 상태 전이 마커 이벤트 추가`
3. `feat: #151 Dashboard debounce push 부품(Flag·Listener·Scheduler·SchedulingConfig) 구현`
4. `chore: #151 Dashboard debounce push 설정(application.yml, build.gradle) 추가`
5. `feat: #151 Worker Claim/Completion/Failure 훅에 상태 전이 이벤트 발행 추가`
6. `test: #151 Dashboard debounce push 부품 단위 테스트 추가`
7. `test: #151 Worker 훅 3종 기존 테스트에 ApplicationEventPublisher Mock 반영`
8. `test: #151 Burst debounce·AFTER_COMMIT PostgreSQL 통합 테스트 추가`

## 8. 완료 조건

- Worker가 Job을 Claim/완료/실패시키는 시점마다 대시보드 집계가 자동으로 다시 계산되어
  `/topic/dashboard` 구독자에게 push된다 (실제 STOMP Client로 검증됨)
- 짧은 시간에 여러 상태 전이가 몰려도(27건 burst) 집계 쿼리는 debounce 주기당 9개 안팎 수준으로
  억제된다 (Hibernate `Statistics`로 실측: `<20`, 억제 없을 때 예상치 `~243`)
- 상태 전이를 발생시킨 Transaction이 롤백되면 push가 발생하지 않는다 (실제 STOMP Client로
  검증됨)
- A담당자가 소유한 `EmbeddingJobClaimService`, `DocumentIndexingCompletionService`,
  `DocumentIndexingFailureService`는 상태 전환 로직을 전혀 바꾸지 않고 이벤트 발행 한 줄씩만
  추가됐다
- `embedding` 도메인이 `dashboard` 패키지를 import하지 않는다 (단방향 의존 유지)
- 전체 빌드(`./gradlew build`)가 회귀 없이 통과한다 (769개 테스트, failures 0, errors 0)
