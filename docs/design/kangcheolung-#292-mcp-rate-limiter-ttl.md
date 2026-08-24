# #292 McpRateLimiter의 windows 맵이 TTL 없이 무한정 커지는 문제 수정

closes #292

---

## 배경

MCP Server 블록의 Rate Limiting(`#117`)이 도입될 때부터 있던 알려진 한계다. `McpRateLimiter`는
사용자·도구 조합(`"userId:toolName"`)별로 분당 호출 횟수를 세는 인메모리 카운터(`Window`)를
`windows`라는 맵에 보관하는데, 이 맵은 한 번 등록된 조합을 **절대 지우지 않는다**. 60초가 지나면
`Window.count`는 다음 호출 때 0으로 리셋되지만, `Window` 객체와 그 키 자체는 서버가 재시작되기
전까지 맵에 영구히 남는다.

`#117` 설계 문서의 "남은 이슈"에 이미 이렇게 기록돼 있었다:

> Rate limit 카운터 맵(`McpRateLimiter.windows`)은 사용자·도구 조합이 늘어날수록 무한정 커진다 —
> TTL 기반 정리(eviction)는 이번 MVP 범위에서 하지 않음, 필요성이 생기면 별도 검토.

즉 신규 발견이 아니라, MVP 범위에서 의도적으로 미뤄뒀던 부채를 이번에 해소하는 이슈다.

---

## 문제 상황

서버가 켜져있는 동안, **사용자 수 × 도구 종류(3종)** 조합만큼 `windows` 맵이 계속 커지기만 하고
절대 줄어들지 않는다. 한 번이라도 `search_documents`/`get_document_detail`/`get_indexing_status`
중 하나를 호출한 적 있는 사용자는, 그 이후로 다시는 접속하지 않아도 해당 조합의 `Window` 객체가
메모리에 계속 남는다.

`Window` 객체 자체는 필드 2개(`long windowStartMillis`, `int count`)라 개별 크기는 작지만,
이런 형태로 무한정 쌓이는 구조는 흔히 말하는 **메모리 누수(memory leak)** 패턴이다. MVP 단계라
사용자 수가 적어 지금 당장 체감되는 영향은 없지만, 사용자 규모가 커지면 결국 문제가 될 수 있다.

---

## 해결 방안

`windows`의 자료구조를 `ConcurrentHashMap`(자동 청소 기능 없음)에서 **Caffeine 캐시**로 교체해,
마지막 접근으로부터 일정 시간(TTL)이 지난 조합은 자동으로 제거되게 했다.

### `build.gradle`

```gradle
implementation 'com.github.ben-manes.caffeine:caffeine'
```

버전은 별도로 명시하지 않았다 — 이 프로젝트가 이미 적용 중인 `org.springframework.boot` 플러그인의
관리 의존성(BOM)에 Caffeine 버전이 포함돼 있어, `spring-ai-bom`처럼 별도 `dependencyManagement`
선언 없이도 버전이 자동으로 맞춰진다.

### `McpRateLimiter.java`

```java
private static final long WINDOW_MILLIS = 60_000;

// 마지막 접근으로부터 이 시간이 지난 사용자·도구 조합은 캐시에서 자동 제거된다.
// rate limit 윈도우(60초)보다 넉넉하게 잡아, 아직 활동 중인 조합이 애매한 타이밍에
// 지워지는 일이 없도록 여유를 둔다.
private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(2);

private final Cache<String, Window> windows;
private final long windowMillis;

public McpRateLimiter() {
    this(WINDOW_MILLIS, TTL_MILLIS);
}

McpRateLimiter(long windowMillis) {
    this(windowMillis, TTL_MILLIS);
}

// 테스트에서 TTL 자동 제거를 짧은 시간 안에 재현할 수 있도록 TTL도 함께 주입받는다.
McpRateLimiter(long windowMillis, long ttlMillis) {
    this.windowMillis = windowMillis;
    this.windows = Caffeine.newBuilder()
            .expireAfterAccess(ttlMillis, TimeUnit.MILLISECONDS)
            .build();
}
```

`checkLimit()` 내부에서 `ConcurrentHashMap.computeIfAbsent(...)`로 하던 "없으면 생성, 있으면
재사용" 로직은 Caffeine의 동일한 원자적 메서드로 그대로 대체했다:

```java
Window window = windows.get(key, k -> new Window(now));
```

**바꾸지 않은 것**: `checkLimit()`의 나머지 로직(60초 고정 윈도우 판단, 카운트 리셋·증가,
`synchronized(window)` 블록)은 전혀 손대지 않았다. "1분에 N번 제한"이라는 로직과 "메모리에서
언제 지워지는가"는 완전히 별개의 관심사라, 이번 수정 범위를 캐시 교체로만 한정했다.

**생성자를 3단계로 유지한 이유**: 기존 `McpRateLimiter(long windowMillis)`(윈도우 길이만
줄이는 테스트 전용 생성자, `#117`의 레이스 컨디션 회귀 테스트가 사용 중)를 그대로 두면서,
TTL까지 짧게 줄여야 하는 새 테스트를 위해 `McpRateLimiter(long windowMillis, long ttlMillis)`를
추가했다. 기존 테스트 호출부(`new McpRateLimiter(50)`)를 한 글자도 안 고쳐도 되게 하기 위한
선택이다.

### 테스트 전용 `size()` 메서드

```java
// 테스트 전용 — TTL 만료로 캐시에서 실제로 제거됐는지 확인한다. cleanUp()은 Caffeine이
// 백그라운드 스레드 없이 다음 접근 시점에야 만료를 정리하는 지연 청소 방식이라, 검증
// 전에 명시적으로 호출해 즉시 정리를 강제한다.
long size() {
    windows.cleanUp();
    return windows.estimatedSize();
}
```

Caffeine의 `expireAfterAccess`는 별도 백그라운드 스레드로 항상 청소하는 방식이 아니라, 캐시에
접근이 있을 때(또는 명시적으로 `cleanUp()`을 호출할 때) 만료된 항목을 정리하는 지연(lazy) 방식이다.
테스트에서 "정말로 지워졌는지"를 결정적으로 확인하려면 `cleanUp()`을 직접 호출해야 해서, 이를
캡슐화한 패키지 전용 헬퍼를 추가했다. 운영 코드 경로에서는 쓰이지 않는다.

---

## 검증

### 신규 단위 테스트

`McpRateLimiterTest`에 TTL 자동 제거를 검증하는 테스트를 추가했다:

```java
@Test
@DisplayName("정상 케이스: TTL이 지나면 사용하지 않은 카운터가 캐시에서 자동 제거된다")
void checkLimit_evictsEntry_afterTtlExpires() throws InterruptedException {
    McpRateLimiter shortTtlLimiter = new McpRateLimiter(60_000, 50);

    shortTtlLimiter.checkLimit(1L, "search_documents", 20);
    assertThat(shortTtlLimiter.size()).isEqualTo(1);

    Thread.sleep(100);

    assertThat(shortTtlLimiter.size()).isZero();
}
```

TTL을 50ms로 줄여 짧은 시간 안에 만료를 재현했다 — `windowMillis`는 이 테스트와 무관해 운영값
그대로(60초) 뒀다.

### 기존 테스트 회귀 확인

`ConcurrentHashMap` → `Cache` 교체가 기존 rate limit 로직(제한 이내 통과, 초과 시 차단, 도구별·
사용자별 분리, 동시성 50스레드, 윈도우 만료 경계 레이스)에 영향을 주지 않는지 기존 6개 테스트를
그대로 재실행해 확인했다 — 전부 통과. 이 6개는 캐시 구현체가 `Map`이든 `Cache`든 상관없이
`checkLimit()`의 동작만 검증하는 테스트라, 교체 여부와 무관하게 그대로 통과해야 하는 게 맞다.

### 결과

- `./backend/gradlew -p backend test --tests "com.opensource.docgrid.domain.mcp.*"`: MCP 도메인
  테스트 전체(7개, 신규 1개 포함) 통과
- `./backend/gradlew -p backend test`: 전체 스위트 통과

---

## 설계 결정 요약

**TTL 2분을 고른 이유**
rate limit 자체가 60초 고정 윈도우인데, 삭제 기준을 그보다 넉넉하게(2배) 잡아 아직 활동 중인
사용자·도구 조합이 애매한 타이밍에 지워지는 일이 없도록 여유를 뒀다. 확정값이 아니라 조정
가능한 상수(`TTL_MILLIS`)로 뒀다.

**Redis 등 외부 저장소로 옮기지 않은 이유**
`#117` 설계 문서에서 이미 "서버 단일 인스턴스를 전제로 한 인메모리 카운터, 다중 인스턴스로
확장될 때 재검토"라고 명시한 그대로다. 이번 이슈는 "무한정 커지는 문제"만 해결하는 것으로
범위를 한정했다 — 다중 인스턴스 확장은 별개 이슈.

**`checkLimit()`의 rate limit 로직을 안 건드린 이유**
이번 문제는 "메모리 관리" 문제지 "속도 제한 정확성" 문제가 아니다. 두 관심사를 섞으면 회귀
위험만 커지므로, 캐시 구현체 교체로 수정 범위를 최소화했다.

---

## 남은 이슈 / TODO

- 없음. `#117`에 남아있던 알려진 한계를 이번 이슈로 해소했다.
