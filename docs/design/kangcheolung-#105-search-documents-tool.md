# #105 search_documents 도구 구현 (F-MCP-02)

closes #105

---

## 배경

MCP Server 블록의 세 번째 이슈. Issue 1(#93)에서 등록만 해둔 빈 핸들러 3개 중 `search_documents`를 실제 로직으로 채운다. 새 검색 로직을 만드는 게 아니라, 검색 블록이 이미 완성한 `SearchFacade`(권한 pre-filter + pgvector 검색 + live check까지 전부 포함)를 그대로 호출하는 얇은 어댑터다.

Issue 2(#96)에서 `SecurityContext`에 userId를 채워는 넣었지만 실제로 도구 핸들러가 그 값을 꺼내 쓰는 코드는 없었다 — 이 이슈에서 그 연결부까지 완성한다.

---

## 사전 검증 — 이번 이슈에서 실제로 확인한 것들

착수 전, Issue 2 설계 문서에 "다음 이슈의 몫"으로 남겨뒀던 미확인 사항을 실제로 검증했다.

**1) `@McpTool` 메서드 안에서 `SecurityContext`가 실제로 보이는가**

로그인 → API 키 발급 → `/mcp` 핸드셰이크 → `tools/call`까지 curl로 전체 플로우를 태우고, 핸들러 안에서 `Thread.currentThread().getName()`과 `SecurityContextHolder.getContext().getAuthentication()`을 임시로 찍어봤다.

```
thread=http-nio-8080-exec-10
auth=UsernamePasswordAuthenticationToken [Principal=mcp-client, Authenticated=true, Details=1, ...]
```

`@McpTool` 메서드가 원래 HTTP 요청을 받은 서블릿 스레드에서 그대로 실행되고(스레드 전환 없음), `McpApiKeyAuthFilter`가 저장한 userId(`Details=1`)가 그대로 조회됐다. SDK 내부에 `reactor-core`가 있어 스레드가 옮겨갈까 걱정했으나, `STREAMABLE`+`SYNC` 조합에선 문제되지 않았다.

**2) "SDK가 필수 파라미터(required)를 자동으로 막아준다"는 기존 문서의 전제가 틀렸음을 발견**

Issue 1 설계 문서와 이번 이슈 To-do 원안에는 "타입/필수값은 SDK가 자동 처리"라고 적혀 있었다. 이 claim은 `tools/list` 응답의 `inputSchema`에 `"required":["query"]`가 찍히는 걸 본 뒤 "스키마에 선언돼 있으니 당연히 강제될 것"이라고 넘겨짚은 것이었고, **실제로 검증한 적이 없었다.**

`query`를 아예 빼고 `tools/call`을 호출해보니:

```json
// 요청
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_documents","arguments":{}}}

// 응답 (수정 전 코드)
{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"Cannot invoke \"String.length()\" because \"query\" is null"}],"isError":true}}
```

`isError: true`는 떴지만 메시지가 우리가 만든 게 아니라 **raw `NullPointerException` 메시지**였다 — `inputSchema`의 `required`는 클라이언트에게 "이 필드는 채워서 보내라"고 알려주는 메타데이터일 뿐, 서버가 요청을 실제로 그 스키마로 재검증하는 게이트는 없다는 뜻이다. `query`가 `null`인 채로 핸들러 코드까지 그대로 들어왔다.

→ **null/blank 체크를 직접 추가해 수정.** `get_document_detail`/`get_indexing_status` 구현 이슈에서도 "필수 파라미터니까 SDK가 막아주겠지"라고 가정하면 안 되고 전부 직접 null 체크가 필요하다는 게 이번에 확정됐다.

---

## 변경 사항 — DocGridMcpTools.searchDocuments

```java
@Component
@RequiredArgsConstructor
public class DocGridMcpTools {

    private static final int MAX_QUERY_LENGTH = 2000;
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;

    private final SearchFacade searchFacade;
    private final ObjectMapper objectMapper;

    @McpTool(name = "search_documents", ..., annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public String searchDocuments(
            @McpToolParam(description = "검색어", required = true) String query,
            @McpToolParam(description = "반환할 최대 결과 수 (기본 5, 1~20)", required = false) Integer topK) {
        // 1. 입력 검증 — SDK는 required를 강제하지 않으므로 null/blank부터 직접 확인
        validateSearchInput(query, topK);

        // 2. McpApiKeyAuthFilter가 SecurityContext에 저장해둔 사용자 식별
        Long userId = currentUserId();

        // 3. 검색 실행 — 권한 pre-filter + live check는 SearchFacade 내부에서 수행
        SearchOutcome outcome = searchFacade.search(userId, new SearchRequest(query, topK, null));

        // 4. results만 JSON으로 직렬화 (SDK가 String 반환값을 텍스트 콘텐츠로 자동 래핑)
        return toJson(outcome.response().results());
    }

    private void validateSearchInput(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER, "query는 필수입니다.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER, "query는 " + MAX_QUERY_LENGTH + "자 이내여야 합니다.");
        }
        if (topK != null && (topK < MIN_TOP_K || topK > MAX_TOP_K)) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER, "topK는 " + MIN_TOP_K + "~" + MAX_TOP_K + " 사이여야 합니다.");
        }
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof Long userId)) {
            throw new DocGridException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new DocGridException(ErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }
}
```

**설계 포인트**

- `SearchFacade.search()`는 `SearchResponse`가 아니라 **`SearchOutcome`**(`response`, `candidates`, `savedResults`)을 반환한다 — `.response()`로 한 번 더 꺼내야 한다. `candidates`/`savedResults`는 RAG 블록용이라 이 도구에서는 쓰지 않는다.
- `CallToolResult`를 직접 만들지 않고 예외를 던지기만 하면 된다 — SDK의 `AbstractSyncMcpToolMethodCallback.createSyncErrorResult(Exception)`가 `CallToolResult.builder().isError(true).addTextContent(cause.getMessage()).build()`를 자동으로 만들어준다는 걸 바이트코드로 확인했다. `DocGridException(ErrorCode, message)`는 그 `message`가 `getMessage()`로 그대로 나가므로, 새 예외 타입을 만들 필요 없이 기존 예외 체계를 그대로 재사용했다.
- `ErrorCode`는 새로 추가하지 않고 기존 `INVALID_PARAMETER`(COMMON-002), `UNAUTHORIZED`(COMMON-007), `INTERNAL_SERVER_ERROR`(COMMON-006)를 재사용했다.
- `SearchRequest`의 `@NotBlank`/`@Min`/`@Max` 검증 애너테이션은 `@Valid`가 붙는 Spring MVC 컨트롤러에서만 작동한다. `@McpTool` 메서드는 그 파이프라인을 안 타므로 `validateSearchInput()`에서 수동으로 같은 규칙을 재현했다.

---

## API 동작 확인 (실제 curl 검증)

### 정상 케이스

```json
// 요청
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_documents","arguments":{"query":"Spring Boot 개발 가이드"}}}

// 응답 — isError:false, text 안에 결과 배열이 JSON 문자열로 담김
{
  "jsonrpc":"2.0","id":2,
  "result":{
    "content":[{"type":"text","text":"[{\"rank\":1,\"documentTitle\":\"Spring Boot 개발 가이드\",\"chunkText\":\"Spring Boot Starter는 의존성 관리를 단순화합니다. @SpringBootApplication 어노테이션으로 애플리케이션을 시작합니다.\",\"pageNo\":null,\"similarityScore\":0.034075}, ... ]"}],
    "isError":false
  }
}
```

Issue 1(#93)/RAG e2e 테스트(#78) 때 넣어둔 더미 임베딩 문서(`Spring Boot 개발 가이드`, `Python 데이터 분석 입문`)에서 청크 4개가 유사도 내림차순으로 반환됐다. `similarityScore`는 더미 임베딩 값이라 실제 의미적 유사도는 아니다(#78 테스트 문서에서도 동일하게 짚은 제약).

`topK: 2`로 재호출하면 결과가 정확히 2개로 제한되는 것도 확인했다.

### 에러 케이스

| 상황 | 요청 | 응답 |
| --- | --- | --- |
| query 누락 | `arguments: {}` | `isError:true`, `"query는 필수입니다."` |
| query 공백만(`"   "`) | | `isError:true`, `"query는 필수입니다."` |
| query 2000자 초과 | | `isError:true`, `"query는 2000자 이내여야 합니다."` |
| topK 범위 초과(`999`) | | `isError:true`, `"topK는 1~20 사이여야 합니다."` |
| 인증 없이 `/mcp` 호출 | `Authorization` 헤더 없음 | HTTP **403** (앱 전체 기존 동작, Issue 2 설계 문서에 이미 기록됨) |
| 검색 결과 0건 | | `isError:false`, `text: "[]"` (정상 응답, 에러 아님) |

---

## 검증

- 신규 단위 테스트 6개(`DocGridMcpToolsTest`): 정상 검색, query null/blank/2000자초과, topK 범위초과(상/하한), 미인증
- 실제 서버 기동 후 curl로 로그인 → API 키 발급 → MCP 핸드셰이크 → `tools/call` 전체 플로우 검증 (위 표 전부)
- `./gradlew test`: **606개 테스트 전체 통과, 실패 0개**

---

## 설계 결정 요약

**SDK의 `required` 스키마 선언을 신뢰하지 않는다**
이번 이슈에서 실측으로 확인된 것처럼, `inputSchema`의 `required`는 클라이언트용 안내일 뿐 서버 측 강제가 아니다. 이후 도구(`get_document_detail`, `get_indexing_status`)도 필수 파라미터는 반드시 직접 null 체크한다.

**예외를 그대로 던지는 방식 채택**
`CallToolResult`를 직접 조립하지 않고 `DocGridException`을 던지기만 하는 이유는, SDK가 이미 예외 → `isError=true` 변환을 대신해주기 때문이다. 이 프로젝트의 기존 예외 처리 컨벤션(`DocGridException(ErrorCode)`)을 그대로 재사용할 수 있어 MCP 전용 별도 규칙을 만들지 않았다.

---

## 남은 이슈 / TODO

- `get_document_detail`, `get_indexing_status` 구현 시 이번에 발견한 "required 미강제" 문제를 동일하게 반영 필요
- Rate Limiting, 출력 정제(chunk_text 길이 제한 등) — 별도 이슈(F-MCP-08/09)

### 다음 단계

`get_document_detail` + `get_indexing_status` 도구 구현 이슈로 이어진다. `PermissionQueryService.canReadDocument()` + `DocumentQueryService`를 재사용한다.
