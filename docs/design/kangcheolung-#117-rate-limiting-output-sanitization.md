# #117 Rate Limiting + 출력 정제 (F-MCP-08/09)

closes #117

---

## 배경

MCP Server 블록의 마지막 기능 이슈(#93 → #96 → #105 → #113 → #117 순으로 진행). 도구 3종(`search_documents`, `get_document_detail`, `get_indexing_status`)이 모두 완성된 뒤에야 "무엇을 감쌀지"가 확정되는 횡단 관심사 두 가지를 구현한다.

- **Rate Limiting**: AI Agent는 사람과 달리 짧은 시간에 반복 호출을 만들어낼 수 있어 도구별 호출 빈도를 제한한다.
- **출력 정제**: null 필드 제거, 과도하게 긴 텍스트 truncate, 예상치 못한 예외의 내부 정보 은닉.

---

## 착수 전 확정한 설계 결정

### 1) `McpOutputSanitizer`라는 별도 클래스는 만들지 않는다

원래 계획했던 세 책임(null 제거/truncate/예외변환)이 실제로는 서로 다른 곳에 자연스럽게 흩어진다:

| 책임 | 실제 구현 위치 |
| --- | --- |
| null 필드 제거 | `DocGridMcpTools` 전용 `ObjectMapper` 복사본 설정 (`setDefaultPropertyInclusion(NON_NULL)`) |
| chunkText truncate | `search_documents`만 필요 — `DocGridMcpTools`의 private 메서드 |
| 예외 → 안전 메시지 변환 | 공통 실행 래퍼 `executeTool()`의 catch 블록 |

새로 만든 클래스는 상태(카운터)를 들고 있어야 하는 `McpRateLimiter` 하나뿐이다.

### 2) Redis는 도입하지 않는다

서버가 1대뿐인 MVP 단계에서 실제로 필요하지 않다. `ConcurrentHashMap` 기반 인메모리 카운터로 충분하며, 다중 인스턴스로 확장될 때 재검토할 사항으로 남겨둔다.

### 3) 부하테스트(JMeter 등)는 도입하지 않되, 동시성 정확성 테스트는 추가한다

처리량 측정용 HTTP 부하테스트는 이번 이슈 범위 밖이다. 다만 여러 요청이 "정확히 같은 순간"에 카운터를 건드릴 때 레이스 컨디션으로 제한이 뚫리는지는 이 프로젝트가 이미 여러 번 검증해온 종류의 위험(`EmbeddingJobClaimService` 등)이라, 순수 JVM 스레드 기반 동시성 유닛 테스트를 추가했다 (DB가 필요 없는 순수 인메모리 로직이라 별도 인프라 없이 가능).

---

## 신규/변경 파일

### ErrorCode.RATE_LIMIT_EXCEEDED (신규)

```java
// MCP
RATE_LIMIT_EXCEEDED(
    HttpStatus.TOO_MANY_REQUESTS,
    "MCP-001",
    "호출 횟수 제한을 초과했습니다. 잠시 후 다시 시도해 주세요."
);
```

이 프로젝트에서 처음 추가하는 MCP 전용 에러코드다. #96·#105·#113은 전부 기존 코드(`INVALID_PARAMETER`, `UNAUTHORIZED` 등)를 재사용했지만, "호출 횟수 초과"에 대응하는 기존 코드가 없어 신규 추가했다.

### McpRateLimiter (신규)

```java
@Component
public class McpRateLimiter {
    private record Window(AtomicInteger count, AtomicLong windowStartMillis) {}
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void checkLimit(Long userId, String toolName, int limitPerMinute) {
        String key = userId + ":" + toolName;
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window(new AtomicInteger(0), new AtomicLong(now)));

        long windowStart = window.windowStartMillis().get();
        if (now - windowStart >= WINDOW_MILLIS && window.windowStartMillis().compareAndSet(windowStart, now)) {
            window.count().set(0);
        }
        if (window.count().incrementAndGet() > limitPerMinute) {
            throw new DocGridException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }
}
```

사용자·도구별(`userId:toolName`)로 분당 고정 윈도우 카운터를 관리한다. 윈도우 만료 감지 시 `compareAndSet`으로 딱 한 스레드만 리셋에 성공하도록 하고, `incrementAndGet()`의 원자성으로 동시 호출에도 정확히 `limitPerMinute`개만 통과함을 보장한다.

### DocGridMcpTools — executeTool() 공통 래퍼 도입

```java
private String executeTool(String toolName, int limitPerMinute, Function<Long, Object> action) {
    Long userId = currentUserId();
    rateLimiter.checkLimit(userId, toolName, limitPerMinute);
    try {
        return toJson(action.apply(userId));
    } catch (DocGridException e) {
        throw e;
    } catch (Exception e) {
        log.error("MCP 도구 실행 중 예상하지 못한 오류 toolName={}", toolName, e);
        throw new DocGridException(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
```

도구 3종이 전부 이 래퍼를 거치도록 리팩터링했다. 각 도구는 자기만의 입력 검증만 앞단에 두고, 나머지(userId 확인 → rate limit → 실행 → 안전한 예외 변환 → 직렬화)는 람다로 위임한다.

**생성자 방식 변경**: null 필드 제거를 위해 주입받은 `ObjectMapper`를 복사·수정해야 해서, `@RequiredArgsConstructor` 대신 명시적 생성자로 전환했다.

```java
public DocGridMcpTools(..., ObjectMapper objectMapper) {
    ...
    // 앱 전체가 공유하는 ObjectMapper Bean을 직접 바꾸면 다른 REST API 응답에도 영향을 주므로
    // 이 클래스 전용 복사본에만 null 제외 설정을 적용한다
    this.objectMapper = objectMapper.copy().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
}
```

이 방식 덕분에 `get_indexing_status`가 그대로 재사용하는 A담당자 소유 `DocumentStatusResponse`(어노테이션을 붙일 수 없는 파일)에도 null 필드 제거가 동일하게 적용된다 — 파일 수정 없이.

**구현 중 발견한 컴파일 경고**: `ObjectMapper.setSerializationInclusion(Include)`는 이 Jackson 버전(2.21.2)에서 deprecated. `setDefaultPropertyInclusion(Include)`로 교체했다 (동일 시그니처의 대체 메서드, 직접 바이트코드로 확인 후 적용).

---

## 실제 검증

### 유닛 테스트 (전체 완료, 673개 통과)

- `McpRateLimiterTest` 5개: 제한 이내 통과, 초과 시 예외, 도구별/사용자별 카운터 분리, **50스레드 동시 호출 시 정확히 20개만 통과**(5회 재실행하여 재현성 확인)
- `DocGridMcpToolsTest`에 6개 추가: rate limit 초과, 예상치 못한 예외 → `INTERNAL_SERVER_ERROR`로 안전 변환(원본 메시지 미노출 검증), chunkText truncate 적용/미적용, `get_document_detail`/`get_indexing_status` null 필드 제거(후자는 우리가 소유하지 않은 `DocumentStatusResponse`에도 적용됨을 확인)

### curl 기반 e2e 검증 — 부분적으로만 완료, 발견한 이슈로 미완주

`search_documents`, `get_document_detail`, `get_indexing_status` 개별 호출과 null 필드 제거(`pageNo`, `processingVersion` 응답에서 사라짐)는 curl로 확인했다. 그러나 **"20회 연속 호출 후 21번째에서 `RATE_LIMIT_EXCEEDED`가 나오는지"를 끝까지 확인하지 못했다** — 검증 도중 이 이슈의 코드와 무관한 사전 버그를 발견해 서버가 반복적으로 멈췄기 때문이다.

#### 발견한 문제: `McpApiKeyAuthFilter`의 DB 커넥션 누수 (이 PR #118의 코드 아님)

`/mcp` 요청을 **누적 5회**(도구 종류 무관) 호출하면 그 이후 모든 DB 관련 기능(로그인 포함)이 30초씩 멈추다 실패하는 현상을 발견했다.

**증거 1 — 애플리케이션 로그**:
```text
Caused by: java.sql.SQLTransientConnectionException: docgrid-local-db-pool - Connection is not
available, request timed out after 30006ms (total=5, active=5, idle=0, waiting=0)
	at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:714)
	...
	at com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService$$SpringCGLIB$$0.authenticate(<generated>)
	at com.opensource.docgrid.domain.mcp.security.McpApiKeyAuthFilter.doFilterInternal(McpApiKeyAuthFilter.java:68)
```

**증거 2 — Postgres 직접 조회** (`docker exec docgrid-postgres17 psql -U docgrid -d docgrid`):
```sql
SELECT pid, state, wait_event, now() - query_start AS duration, left(query, 50)
FROM pg_stat_activity WHERE datname = 'docgrid' AND pid <> pg_backend_pid();
```
```text
  pid  | state | wait_event |    duration     | query
-------+-------+------------+------------------+--------
 27839 | idle  | ClientRead | 00:12:42.654485  | COMMIT
 ... (5개 전부 동일 패턴)
```

애플리케이션(HikariCP)은 "5개 전부 사용 중"이라고 하는데, DB(Postgres)는 "5개 전부 COMMIT까지 끝내고 12분 넘게 idle"이라고 한다 — 즉 SQL 작업은 정상 종료됐지만 자바 코드가 그 커넥션을 풀에 반납하지 않고 있다. 실패 지점은 도구 로직이 아니라 **모든 `/mcp` 요청이 공통으로 거치는 `McpApiKeyAuthFilter → McpAccessTokenCommandService.authenticate()`** 단계였다.

**Issue 5 코드가 원인이 아닌 이유**: `McpRateLimiter`와 `executeTool()`은 DB를 전혀 사용하지 않는다. `McpApiKeyAuthFilter`/`McpAccessTokenCommandService`는 Issue 2(#96)에서 이미 머지된 코드로, 그동안 curl 테스트를 짧게(호출 몇 번 이내로)만 해봐서 누적 5회를 넘긴 적이 없어 이번에 처음 드러난 것으로 보인다.

**미확정 가설**: `McpApiKeyAuthFilter`가 일반 `@RestController`가 아니라 순수 서블릿 `Filter`에서 `@Transactional` 서비스를 직접 호출하는 구조라, Spring이 평소 컨트롤러 요청 종료 시 자동으로 수행하는 트랜잭션/커넥션 정리가 이 경로에서 누락되는 것이 아닌지 의심된다 — 아직 직접 증명하지 않았다.

**처리 방침**: 이 버그는 이번 이슈(#117)의 범위가 아니라 #96 코드의 결함이므로, 별도의 버그 수정 이슈로 분리해 새 브랜치에서 다룬다. #117은 유닛 테스트(rate limiter 동시성 포함)로 이미 충분히 검증된 상태로 마무리한다.

---

## 검증 요약

- `./gradlew test`: **673개 테스트 전체 통과, 실패 0개**
- curl e2e: 도구 3종 개별 호출·null 필드 제거는 확인 완료. rate limit의 20회 초과 시나리오는 위 별도 버그로 인해 끝까지 확인하지 못함 — 버그 수정 후 재검증 필요

---

## 남은 이슈 / TODO

- **[별도 이슈로 분리]** `McpApiKeyAuthFilter`의 DB 커넥션 누수 수정 — 원인 규명 및 수정 후, 이번에 못 끝낸 20회 연속 호출 e2e 검증을 재수행해야 함
- Rate limit 카운터 맵(`McpRateLimiter.windows`)은 사용자·도구 조합이 늘어날수록 무한정 커진다 — TTL 기반 정리(eviction)는 이번 MVP 범위에서 하지 않음, 필요성이 생기면 별도 검토

### 다음 단계

커넥션 누수 버그 수정 후, Claude Desktop 실연동 검증(마지막 테스트 이슈)으로 이어간다.
