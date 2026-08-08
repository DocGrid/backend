# #120 /mcp DB 커넥션 누수 수정

closes #120

---

## 배경

#117 (Rate Limiting + 출력 정제) 작업 중 curl e2e 검증을 하다가, `/mcp`를 누적 5회(도구 종류 무관) 호출하면 그 이후 로그인 포함 모든 DB 관련 기능이 30초씩 멈추다 실패하는 현상을 우연히 발견했다. 당시 증거(HikariCP `active=5, idle=0` 타임아웃 로그 + Postgres `pg_stat_activity`에서 5개 커넥션이 전부 `idle`+`COMMIT` 상태로 방치)까지는 확보했지만, 정확한 원인은 규명하지 못한 채 "필터에서 트랜잭션 서비스를 직접 호출하는 구조가 의심된다"는 미확정 가설만 남기고 #117 범위 밖으로 분리했다. 그 후속으로 만든 게 이 이슈(#120)다.

원인 코드 자체는 #96(MCP 인증 인프라)에서 도입됐지만, #117 작업 중 처음 발견됐고 #120에서 원인을 규명·수정했다.

---

## 원인 조사 과정

### 1) 재현 시도 1 — `tools/list`로는 재현 안 됨

로컬에 fix/120 브랜치로 앱을 띄우고, MCP 토큰을 발급해서 `/mcp`에 `initialize` → `tools/list`(MCP 프로토콜 메타데이터 조회, 실제 `@McpTool` 로직은 안 탐)를 순차 20회, 병렬 10회, 세션 ID 없이 에러가 나는 경로까지 섞어서 도합 30회 넘게 호출했다. **한 번도 재현되지 않았다** — 커넥션 개수가 계속 2~4개 선에서 유지됐고, 모든 호출이 100ms 이내로 빠르게 응답했다.

이 결과로 "필터의 `authenticate()`가 원인"이라는 #117의 미확정 가설에 의문이 생겼다. `tools/list`도 `/mcp`인 이상 `McpApiKeyAuthFilter → McpAccessTokenCommandService.authenticate()`를 매번 똑같이 타는데, 그게 안 새기 때문이다.

### 2) 재현 시도 2 — 실제 도구(`tools/call`)로는 재현됨

`tools/call`로 `search_documents`를 5회 연속 호출하니 **#117에서 봤던 것과 동일한 패턴으로 재현됐다**:

**앱 로그**:
```text
2026-08-08T15:24:22.135+09:00 ERROR ... o.h.engine.jdbc.spi.SqlExceptionHelper : docgrid-local-db-pool - Connection is not available, request timed out after 30010ms (total=5, active=5, idle=0, waiting=0)
Caused by: org.hibernate.exception.JDBCConnectionException: Unable to acquire JDBC Connection ...
Caused by: java.sql.SQLTransientConnectionException: docgrid-local-db-pool - Connection is not available, request timed out after 30010ms (total=5, active=5, idle=0, waiting=0)
	at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:714)
```

**Postgres 직접 조회**:
```text
  pid  | state | wait_event |    duration     |  left
-------+-------+------------+------------------+--------
 47795 | idle  | ClientRead | 00:00:57.812522  | COMMIT
 47794 | idle  | ClientRead | 00:00:58.084993  | COMMIT
 47796 | idle  | ClientRead | 00:00:57.609905  | COMMIT
 47793 | idle  | ClientRead | 00:00:58.288372  | COMMIT
 47592 | idle  | ClientRead | 00:00:58.702759  | COMMIT
(5 rows)
```

6번째 호출은 30초 대기 후 403으로 실패했다(인증 필터가 새 커넥션을 못 받아 `authenticate()`가 타임아웃 → 인증 실패로 처리되어 Spring Security 기본 동작인 403 반환).

### 3) 원인 좁히기 — `get_document_detail`로도 동일 재현

`search_documents`만의 문제(예: `SearchFacade` 내부 로직)인지 확인하기 위해 앱을 재기동하고 `get_document_detail`을 5회 호출했다. **동일하게 5회 만에 재현됐다** (idle 커넥션 5개, 마지막 쿼리가 `document_versions` SELECT인 채로 방치).

즉 **도구 3종 공통** — `search_documents`, `get_document_detail`, `get_indexing_status` 어떤 것이든 실제 `@McpTool` 로직이 DB(JPA)에 접근하는 순간 새는 것이지, 특정 도구나 특정 서비스(`SearchFacade`, `DocumentRepository` 등)의 버그가 아니었다.

### 4) 원인 확정 — `spring.jpa.open-in-view`

이 프로젝트 어디에도 `spring.jpa.open-in-view`가 명시적으로 설정돼 있지 않아, Spring Boot 기본값인 `true`가 그대로 적용되고 있었다.

- OSIV(Open Session/Connection In View)가 켜져 있으면, JPA가 쓰는 JDBC 커넥션이 "서블릿 요청이 완전히 끝날 때"까지 스레드에 바인딩된 채로 유지되다가, 요청 완료 시점에 반납된다.
- `/mcp`는 일반 동기 REST 컨트롤러가 아니라 MCP SDK의 **Streamable HTTP 전송 방식**(`Accept: text/event-stream` 기반)으로 응답을 처리한다. 이 처리 경로가 Spring MVC 입장에서 "요청이 정상적으로 완전히 끝났다"는 신호를 OSIV의 정리(cleanup) 훅에 제대로 전달하지 못하는 것으로 보인다.
- 그 결과 `tools/list`처럼 DB를 아예 안 건드리는 호출은 OSIV가 커넥션을 바인딩할 일 자체가 없어서 문제가 없었고, 실제 도구가 Repository/Service를 호출해 커넥션이 바인딩되는 순간부터는 그 커넥션이 절대 안 풀리는 것이다.

**가설 검증**: `./gradlew bootRun --args='--spring.jpa.open-in-view=false'`로 임시로 꺼서 동일 시나리오(`search_documents` 8회 + `get_document_detail`/`get_indexing_status` 5회씩, 총 18회)를 재실행했다. **커넥션 개수가 시작부터 끝까지 2개로 고정, 전혀 안 늘었다.** 이걸로 원인을 확정했다.

기존 #117의 "미확정 가설"(필터가 서블릿 `Filter`에서 `@Transactional` 서비스를 직접 호출하는 구조가 원인)은 **기각한다** — `tools/list`도 그 필터를 똑같이 타지만 안 샜으므로, 필터 자체는 원인이 아니다.

---

## 해결 방안

`spring.jpa.open-in-view`를 전역으로 끄면 `/mcp` 외의 40개가 넘는 다른 API 전부에도 동작 변화가 생긴다. 이 프로젝트는 "모든 연관관계 LAZY, 필요 시 JOIN FETCH"(`java-style.md`) 원칙을 지키고 있어 위험은 낮아 보이지만, 문제가 확인된 범위(`/mcp`)보다 넓게 바꿀 이유가 없어서 **`/mcp`만 정확히 격리**하는 방식을 택했다.

### `application.yml`

```yaml
spring:
  jpa:
    open-in-view: false
```

Spring Boot가 자동으로 등록하는 전역 OSIV 인터셉터를 끈다.

### `WebMvcConfig.java`

```java
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        EntityManagerFactory entityManagerFactory = entityManagerFactoryProvider.getIfAvailable();
        if (entityManagerFactory == null) {
            return;
        }
        OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
        interceptor.setEntityManagerFactory(entityManagerFactory);
        registry.addWebRequestInterceptor(interceptor).excludePathPatterns("/mcp");
    }
}
```

대신 우리가 직접 OSIV 인터셉터를 등록하되 `excludePathPatterns("/mcp")`로 `/mcp`만 빼고 나머지 모든 경로에 적용한다. 즉 `/mcp` 외의 API는 지금까지와 동작이 100% 동일하고, `/mcp`만 "요청이 끝나는 즉시 커넥션 반납"으로 바뀐다.

**`EntityManagerFactory`를 `ObjectProvider`로 주입받는 이유**: 처음엔 생성자 필드로 직접 주입했는데, `@WebMvcTest` 슬라이스 테스트(JPA 계층은 안 올리고 컨트롤러 계층만 가볍게 띄움)에서도 `WebMvcConfig`가 `WebMvcConfigurer` 구현체라 스캔 대상이 되어 `EntityManagerFactory` 빈을 못 찾아 컨텍스트 자체가 실패했다(`WorkerAdminControllerTest`, `IndexingJobAdminControllerTest` 등 9개 테스트 케이스 FAILED). `ObjectProvider`로 바꿔서 빈이 없으면 인터셉터 등록 자체를 건너뛰도록 해 해결했다.

---

## 실제 검증

### 자동 테스트 (`./gradlew test`)

- `ObjectProvider` 적용 전: 674개 중 115개 FAILED (`NoSuchBeanDefinitionException` — `@WebMvcTest` 슬라이스에서 `EntityManagerFactory` 없음)
- `ObjectProvider` 적용 후: **674개 전체 통과, 실패 0개**

### 수동 e2e 재현 테스트 (수정 후)

수정 반영 후 앱을 재기동해서 아래를 재확인했다.

- `search_documents` 25연속 호출: 전부 200, 커넥션 개수 2개로 고정(수정 전엔 5회에서 고갈됐던 것과 대조)
- `search_documents`/`get_document_detail`/`get_indexing_status` 3종을 섞어 20회 호출: 전부 200, 커넥션 개수 그대로 유지
- `/mcp`가 아닌 경로(`/auth/login`)도 수정 후 여전히 정상 동작 확인 — `/mcp`만 격리한 설정이 다른 API에 영향을 안 준다는 것을 뒷받침

---

## 검증 요약

- `./gradlew test`: **674개 테스트 전체 통과, 실패 0개**
- curl e2e: 수정 전 5회 만에 100% 재현되던 커넥션 고갈이, 수정 후 25연속 + 혼합 20회 호출에서 전혀 재현 안 됨
- `/mcp` 외 경로(`/auth/login`) 정상 동작 확인

---

## 남은 이슈 / TODO

- ~~#117에서 못 끝낸 "20회 연속 호출 → 21번째에서 `RATE_LIMIT_EXCEEDED`" curl e2e 검증~~ → 이번 수정으로 커넥션 고갈 없이 25연속 호출까지 확인했으므로, 남은 건 정확히 21번째에서 `RATE_LIMIT_EXCEEDED`가 뜨는지 숫자 경계만 재검증하면 됨 (별도 소요 시간 크지 않음)
- Claude Desktop 실연동 검증(MCP 로드맵의 마지막 테스트 이슈)으로 이어간다.
