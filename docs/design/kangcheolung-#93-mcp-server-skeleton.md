# #93 MCP 서버 골격 — Spring AI 1.1.x 연동 및 도구 3종 스켈레톤 등록 (F-MCP-01)

closes #93

---

## 배경

DocGrid의 검색·문서조회·인덱싱상태 조회 기능을 MCP(Model Context Protocol) 표준으로 노출해 Claude Desktop 같은 AI Agent가 직접 호출할 수 있게 하는 MCP Server 블록의 첫 번째 이슈다.

**새 기능을 만드는 게 아니라, 이미 완성된 검색/권한/문서조회 로직(`SearchFacade`, `PermissionQueryService`, `DocumentQueryService`)을 표준 프로토콜로 감싸는 어댑터 레이어**를 만드는 게 이 블록 전체의 목표다.

### 왜 이 이슈가 독립적으로 먼저 필요한가

MCP 명세를 처음 설계할 때는 `POST /mcp {method: "list_tools"}` 같은 커스텀 JSON 프로토콜을 직접 만드는 방향이었다. 하지만 실제 MCP 표준은 **JSON-RPC 2.0** (`tools/list`, `tools/call`)이라, 커스텀 프로토콜을 그대로 구현하면 실제 Claude Desktop 같은 클라이언트와는 연동되지 않는다.

리서치 결과 공식 MCP Java SDK를 감싼 **Spring AI MCP Boot Starter**를 쓰면 표준 프로토콜 파싱/직렬화를 대신 처리해준다는 걸 확인했다. 다만:

- Spring AI **2.0**은 Spring Boot 4.0 + Java 21이 필수라 이 프로젝트(Spring Boot 3.5.16 / Java 17)와 호환되지 않는다 → **Spring AI 1.1.x 라인**(Spring Boot 3.3+ 지원) 채택.
- 이 SDK 연동은 이 프로젝트에서 처음 시도하는 것이라, "어노테이션 스캔이 실제로 동작하는가"가 검증되지 않은 리스크였다. 그래서 실제 검색/권한 로직을 붙이기 전에 **빈 도구 3개만 등록해서 SDK 연동 자체를 먼저 검증**하는 것이 이 이슈의 목적이다.

---

## 사전 기술 검증

문서 요약만 믿지 않고 실제 의존성 jar을 받아서 `javap`로 클래스를 직접 열어 확인했다.

```bash
./gradlew dependencies --configuration compileClasspath | grep -iE "mcp|spring-ai"
```

확인된 사실:

| 항목 | 확인 결과 |
| --- | --- |
| `@McpTool` / `@McpToolParam` 패키지 | `org.springaicommunity.mcp.annotation` — Spring AI 코어가 아니라 `org.springaicommunity:mcp-annotations`(별도 커뮤니티 프로젝트) 소속. Spring AI 1.1.x가 이 라이브러리를 의존성으로 끌어다 씀. |
| `@McpTool` 속성 | `name()`, `description()`, `annotations()`, `generateOutputSchema()`, `title()`, `metaProvider()` |
| `@McpToolParam` 속성 | `required()`, `description()` — `name()` 없음. 파라미터 이름은 컴파일러의 `-parameters` 플래그로 보존된 실제 자바 파라미터명을 사용 (Spring Boot Gradle 플러그인이 기본으로 활성화, 프로젝트 컴파일 결과에서 `MethodParameters` 속성으로 실제 확인됨). |
| `CallToolResult` 위치 | `io.modelcontextprotocol.spec.McpSchema.CallToolResult` (MCP 공식 Java SDK, `io.modelcontextprotocol.sdk:mcp-core`) |
| 설정 프로퍼티 키 | `spring.ai.mcp.server.{name,protocol,type,enabled}`, `spring.ai.mcp.server.annotation-scanner.enabled` — `McpServerProperties`/`McpServerAnnotationScannerProperties` 클래스 필드로 직접 확인 |
| `protocol` enum 값 | `SSE`, `STREAMABLE`, `STATELESS` |
| `type` enum 값 | `SYNC`, `ASYNC` |
| 기본 엔드포인트 경로 | `/mcp` (`McpServerStreamableHttpProperties.mcpEndpoint` 기본값) |
| 도구 등록 메커니즘 | `ServerAnnotatedMethodBeanPostProcessor`(`BeanPostProcessor`)가 모든 빈을 스캔해 `@McpTool` 메서드를 찾아 `ServerMcpAnnotatedBeans`에 등록 → `McpServerSpecificationFactoryAutoConfiguration`이 이를 `McpServerFeatures.SyncToolSpecification` 목록으로 변환 |

**중요한 발견 — 알려진 SDK 버그 회피 경로 확인**: GitHub 이슈 [#4882](https://github.com/spring-projects/spring-ai/issues/4882)는 `spring-ai-starter-mcp-server-webmvc:1.1.0`에서 **STATELESS+SYNC** 모드로 AOP 프록시된 빈에 `@McpTool`을 붙이면 도구 등록이 실패하는 버그다(아직 OPEN). 하지만 위 표에서 확인했듯 `STREAMABLE`+`SYNC` 조합은 `StatelessServerSpecificationFactoryAutoConfiguration`이 아니라 **`McpServerSpecificationFactoryAutoConfiguration`**을 타는 전혀 다른 클래스 경로다 — 즉 구조적으로 이 버그의 영향을 받지 않는다는 것을 코드로 직접 확인했다. (`DocGridMcpTools`도 `@Transactional` 등 AOP 프록시를 유발하는 어노테이션 없이 순수 `@Component`로 유지해 이중으로 안전하게 만들었다.)

또한 이슈 [#4392](https://github.com/spring-projects/spring-ai/issues/4392)(`annotation-scanner.enabled=true`에도 `@McpTool` 빈이 등록 안 되는 버그, 1.1.0-M1에서 보고)는 milestone 1.1.1로 CLOSED됨을 확인했고, `spring-ai-bom` 최신 1.1.x 패치인 **1.1.8**을 사용해 이 버그도 회피했다.

---

## 신규/변경 파일

### build.gradle

```gradle
dependencyManagement {
    imports {
        mavenBom "org.springframework.ai:spring-ai-bom:1.1.8"
    }
}

dependencies {
    implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webmvc'
}
```

`1.1.8`은 `repo1.maven.org`의 `maven-metadata.xml`을 직접 조회해 확인한 1.1.x 라인의 최신 패치 버전이다 (`<latest>`/`<release>`는 2.0.0이지만, 2.0.0은 Spring Boot 4.0 필수라 배제).

---

### application.yml

```yaml
spring:
  ai:
    mcp:
      server:
        name: docgrid-mcp-server
        protocol: STREAMABLE
        type: SYNC
        annotation-scanner:
          enabled: true
```

- `protocol: STREAMABLE`: stdio가 아닌 HTTP 기반 원격 서버로 동작하도록 설정 (DocGrid는 이미 Spring Boot 웹서버이므로 자연스러운 선택).
- `type: SYNC`: 요청을 동기적으로 처리하는 서버 모드. DocGrid의 기존 서비스 계층(`SearchFacade` 등)이 전부 동기 방식이라 일치시킴.
- `annotation-scanner.enabled: true`: `@McpTool` 어노테이션이 붙은 빈을 자동 스캔해 도구로 등록.

---

### domain/mcp/tool/DocGridMcpTools.java

```java
@Component
public class DocGridMcpTools {

    @McpTool(name = "search_documents",
        description = "사용자 질문과 관련된 문서 chunk를 벡터 검색으로 찾는다. 권한이 있는 문서만 반환된다.")
    public String searchDocuments(
            @McpToolParam(description = "검색어", required = true) String query,
            @McpToolParam(description = "반환할 최대 결과 수 (기본 5, 1~20)", required = false) Integer topK) {
        // TODO: SearchFacade 연동 (다음 이슈에서 구현)
        return "not implemented";
    }

    @McpTool(name = "get_document_detail",
        description = "특정 문서의 메타데이터와 현재 버전 정보를 조회한다. 권한이 있는 문서만 조회 가능하다.")
    public String getDocumentDetail(
            @McpToolParam(description = "문서 ID", required = true) Long documentId) {
        // TODO: PermissionQueryService + DocumentQueryService 연동 (다음 이슈에서 구현)
        return "not implemented";
    }

    @McpTool(name = "get_indexing_status",
        description = "특정 문서 또는 버전의 인덱싱 상태(PENDING/PROCESSING/INDEXED/FAILED)를 조회한다.")
    public String getIndexingStatus(
            @McpToolParam(description = "문서 ID", required = false) Long documentId,
            @McpToolParam(description = "버전 ID", required = false) Long versionId) {
        // TODO: PermissionQueryService + DocumentQueryService 연동 (다음 이슈에서 구현)
        return "not implemented";
    }
}
```

- 반환 타입을 `CallToolResult`가 아니라 `String`으로 둔 이유: SDK의 `AbstractMcpToolMethodCallback.convertValueToCallToolResult(Object)`가 `String`/POJO 같은 단순 반환값을 자동으로 `CallToolResult`(TEXT 콘텐츠)로 감싸준다. 스텁 단계에서는 이 자동 래핑으로 충분하다.
- 파라미터 시그니처는 F-MCP-02/03/04 명세의 입력 스키마(`query`+`topK`, `documentId`, `documentId`+`versionId`)를 그대로 반영.

---

### global/config/SecurityConfig.java

```java
.requestMatchers("/auth/signup", "/auth/login").permitAll()
// TODO: 임시 permitAll — 다음 이슈에서 McpApiKeyAuthFilter로 교체 예정
.requestMatchers("/mcp/**").permitAll()
.requestMatchers("/admin/**").hasRole("ADMIN")
```

MCP 인증(F-MCP-07, API 키 방식)은 다음 이슈에서 구현하므로, 이번 이슈에서는 `/mcp/**`를 임시로 인증 예외 처리하고 골격 검증에만 집중한다.

---

## 완료 조건 검증

로컬로 실제 서버를 기동해 JSON-RPC `initialize` → `tools/list` 흐름을 curl로 직접 호출해 확인했다.

**initialize 응답:**
```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{...},"serverInfo":{"name":"docgrid-mcp-server","version":"1.0.0"}}}
```

**tools/list 응답 (일부):**
```json
{
  "jsonrpc":"2.0","id":2,
  "result":{
    "tools":[
      {
        "name":"search_documents",
        "description":"사용자 질문과 관련된 문서 chunk를 벡터 검색으로 찾는다. 권한이 있는 문서만 반환된다.",
        "inputSchema":{
          "type":"object",
          "properties":{
            "query":{"type":"string","description":"검색어"},
            "topK":{"type":"integer","format":"int32","description":"반환할 최대 결과 수 (기본 5, 1~20)"}
          },
          "required":["query"]
        }
      }
      // get_document_detail, get_indexing_status 도 동일하게 정상 반환됨
    ]
  }
}
```

애플리케이션 로그에서도 `Registered tools: 3`을 확인했다. 도구 3종의 `name`/`description`/`inputSchema`(필수값 포함)가 어노테이션 메타데이터로부터 정확히 자동 생성됨을 실제 데이터로 증명했다 — 이 이슈의 목적이었던 "SDK 연동 리스크 검증"이 해소됐다.

---

## 에러 코드 (참고, 이번 이슈에서는 미구현)

실제 로직이 없는 스텁 단계라 에러 케이스는 아직 발생하지 않는다. 다음 이슈들에서 아래 명세를 따라 구현할 예정이다.

| 코드 | 의미 | 구현 예정 이슈 |
| --- | --- | --- |
| `VALIDATION_ERROR` | 입력 파라미터 검증 실패 (query 2000자 초과, topK 범위 초과 등) | search_documents / detail+status 도구 이슈 |
| `UNAUTHORIZED` | 인증 실패 (API 키 없음/폐기됨/불일치) | 인증 인프라 이슈 |
| `PERMISSION_DENIED` | 권한 없음 | detail+status 도구 이슈 |
| `DOCUMENT_NOT_FOUND` | 문서 없음 | detail+status 도구 이슈 |
| `RATE_LIMIT_EXCEEDED` | 호출 횟수 초과 | rate limit 이슈 |

---

## 설계 결정 요약

**Spring AI 2.0이 아닌 1.1.x 채택**
2.0은 Spring Boot 4.0 + Java 21이 필수라 이 프로젝트와 호환 불가. 1.1.x는 Spring Boot 3.3+를 지원해 현재 스택(3.5.16)과 맞는다.

**빈 도구 등록을 별도 이슈로 분리**
SDK 연동이 이 프로젝트에서 처음이라 검증되지 않은 리스크였다. 실제 검색/권한 로직을 붙이기 전에 등록 자체가 되는지부터 확인해, 만약 안 됐다면 `@Tool` + `ToolCallbackProvider` 수동 등록으로 전환하는 결정을 이 시점에 내릴 수 있게 리스크를 앞당겼다.

**`/mcp/**` 임시 permitAll**
인증 인프라(`mcp_access_tokens` 재사용, API 키 필터)는 별도 이슈 규모라 이번 이슈 범위에서 뺐다. TODO 주석으로 다음 이슈에서 반드시 교체하도록 명시.

---

## 남은 이슈 / TODO

### 다음 이슈에서 반영 필요

- **`@McpTool`의 `annotations` 속성 미설정**: 현재 `tools/list` 응답에 `readOnlyHint: false`, `destructiveHint: true`가 기본값으로 나간다. 이 3개 도구는 전부 조회 전용(read-only)인데 반대로 선언되어 있어, MCP 클라이언트가 불필요한 승인(approval) UX를 붙일 수 있다. 실제 로직을 구현하는 이슈(search_documents / detail+status)에서 `@McpTool(..., annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))`로 명시할 것.
- **`CallToolResult` 명시적 사용 필요**: `VALIDATION_ERROR`/`PERMISSION_DENIED` 같은 에러를 `isError=true`로 표현하려면 단순 `String` 반환의 자동 래핑으로는 부족할 가능성이 높다 — `CallToolResult.builder().isError(true)...`를 명시적으로 써야 하는지 실제 로직 구현 시 확인.
- **`/mcp/**` permitAll 제거**: 인증 인프라 이슈에서 `McpApiKeyAuthFilter`로 교체.

### 다음 단계

인증 인프라(API 키 발급/조회/폐기 + 인증 필터) 구현 이슈로 이어진다. 이후 `search_documents` → `get_document_detail`/`get_indexing_status` → Rate Limiting/출력 정제 → Claude Desktop 연동 검증 순서로 진행한다.
