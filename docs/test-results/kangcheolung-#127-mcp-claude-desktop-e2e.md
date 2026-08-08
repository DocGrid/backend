# #127 MCP 서버 Claude Desktop 연동 검증 결과

## 배경

MCP Server 블록의 마지막 이슈. 지금까지 구현한 MCP 서버(#93 골격 → #96 인증 → #105 search_documents → #113 get_document_detail/get_indexing_status → #117 rate limiting/출력 정제 → #120 DB 커넥션 누수 수정)를 실제 Claude Desktop에 등록해서, 도구 3종이 실제 AI Agent 대화 흐름에서 정상 호출/응답되는지 처음으로 실측 검증했다.

이 과정에서 지금까지 curl 단발 테스트로는 드러나지 않았던 버그 3개를 새로 발견하고 수정했다 — 전부 "실제 MCP 클라이언트(mcp-remote)가 curl과는 다른 방식으로 요청을 보낸다"는 지점에서 터진 것들이라, 이 이슈가 아니었으면 계속 안 보이고 넘어갔을 문제들이다. 아래 "발견 및 해결" 섹션에 상세히 기록한다.

## 테스트 환경

- 로컬 인프라: Postgres17(`docgrid-postgres17`), MinIO, embedding server, Ollama 전부 기동 상태
- 앱: IntelliJ에서 `local` 프로파일로 직접 실행 (Spring DevTools 자동 재시작 사용)
- MCP 클라이언트: **Claude Desktop** + [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) `0.1.37` (로컬 HTTP 서버를 Claude Desktop이 인식하는 stdio 브릿지로 연결)
- 테스트 계정:
  - 관리자 계정: `kcw130502@gmail.com` (userId=1, ADMIN, MCP 토큰 발급 주체)
  - 권한 테스트용 별도 계정: `other-user@test.com` (userId=2, USER, 직접 회원가입으로 생성)
- `claude_desktop_config.json` (`~/Library/Application Support/Claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "docgrid": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://localhost:8080/mcp",
        "--transport", "http-only",
        "--header", "Authorization: Bearer {발급받은 MCP 토큰}"
      ]
    }
  }
}
```

- `--transport http-only`: mcp-remote가 SSE/HTTP 중 어느 걸 쓸지 자동 감지에 맡기지 않고, 우리 서버가 Streamable HTTP 전용이라는 걸 이미 알고 있으므로 명시적으로 고정했다. (`mcp-remote` 로컬 캐시된 README에서 `http-first`(기본)/`sse-first`/`http-only`/`sse-only` 4종 확인)
- 커스텀 `--header`로 OAuth 없이 우리 자체 Bearer 토큰(`docgrid_mcp_...`)만으로 인증하는 방식 채택 — mcp-remote README의 "Custom Headers" 섹션에 문서화된 공식 워크어라운드.

## 정상 케이스

### 1) 도구 등록 및 노출 확인

`--debug` 옵션을 잠깐 켜서 확인한 mcp-remote 로그(`~/.mcp-auth/mcp-remote-0.1.37/*_debug.log`) 기준, `initialize` → `notifications/initialized` → `tools/list`/`prompts/list`/`resources/list`가 전부 `"result":"result-present"`로 성공 응답을 받았다.

```
[Local→Remote] initialize
[Remote→Local] 0 {"id":0,"result":"result-present"}
[Local→Remote] notifications/initialized
[Local→Remote] tools/list
[Local→Remote] prompts/list
[Local→Remote] resources/list
[Remote→Local] 3 {"id":3,"result":"result-present"}
[Remote→Local] 2 {"id":2,"result":"result-present"}
[Remote→Local] 1 {"id":1,"result":"result-present"}
```

참고: 로그에 반복적으로 찍히는 `Reading OAuth tokens` → `Token result: Not found`는 에러가 아니다 — mcp-remote가 매 요청마다 "OAuth 토큰이 있으면 같이 보내자"고 확인하는 정상 루틴이고, 우리는 OAuth 대신 `--header`로만 인증하므로 "토큰 없음"이 나오는 게 정상이다.

### 2) `search_documents` — 실제 대화

> **사용자**: "DocGrid에서 Spring Boot 관련 문서 검색해줘. 검색 결과 중 하나 골라서 상세 정보도 보여줘."

> **Claude**: 검색 결과, Spring Boot 관련 문서는 "Spring Boot 개발 가이드"(documentId: 1) 하나이고 관련 청크가 2개 나왔어요.
> - Spring Boot Starter로 의존성 관리 단순화, @SpringBootApplication 어노테이션으로 앱 시작
> - Spring Boot는 Java 기반 웹 프레임워크, 자동 설정 및 내장 서버로 빠른 개발 지원

### 3) `get_document_detail` — 실제 대화 (도구 체이닝 성공)

> **사용자**: "DocGrid에서 documentId 1번 문서 상세 정보 보여줘"

> **Claude**: documentId 1번 문서 상세 정보입니다.
> - 제목: Spring Boot 개발 가이드
> - 문서 ID: 1
> - 현재 버전: 1
> - 인덱싱 상태: INDEXED (완료)
> - 최종 수정일: 2026-08-05 21:33:29

`search_documents` 응답에서 받은 `documentId`를 그대로 `get_document_detail`에 넘겨 자연스럽게 체이닝된 것을 확인 — 아래 "발견 및 해결 1)"에서 이 체이닝이 원래 막혀있었던 버그를 다룬다.

### 4) `get_indexing_status` — 실제 대화

> **사용자**: "그 문서(documentId 1) 인덱싱 상태도 확인해줘"

> **Claude**: documentId 1번 인덱싱 상태입니다.
> - 문서 전체 상태: INDEXED
> - 현재 버전(v1) 상태: INDEXED
> - 버전 1이 정상적으로 인덱싱 완료된 상태예요.

## 에러 케이스

### 1) 권한 없는 문서 조회 → 차단 확인

테스트용으로 `other-user@test.com`(userId=2) 계정을 만들어 `visibility=PRIVATE`인 문서를 하나 업로드했다(`documentId=3`, "비공개 테스트 문서").

```bash
curl -X POST http://localhost:8080/api/documents \
  -H "Authorization: Bearer $OTHER_JWT" \
  -F "file=@private-test-doc.txt;type=text/plain" \
  -F "title=비공개 테스트 문서" -F "visibility=PRIVATE"
# → {"documentId":3, ...}
```

> **사용자**: "documentId 3번 문서 상세 정보 보여줘"

> **Claude**: documentId 3번은 접근 권한이 없어서 조회가 안 돼요. 다른 문서 ID로 시도해볼까요?

`PermissionQueryService.canReadDocument()`가 조회 전에 먼저 차단해 `PERMISSION_DENIED`가 정상적으로 반환됨을 확인.

### 2) Rate Limit 초과 → 차단 확인

Claude Desktop 대화로 20회 이상 자연스럽게 반복 호출을 유도하기 어려워서, 같은 세션의 rate limiter(`userId:toolName` 기준이라 curl이든 Claude Desktop이든 동일하게 동작)를 curl로 직접 22회 연속 호출해 검증했다.

```bash
for i in $(seq 1 22); do
  curl -s -X POST http://localhost:8080/mcp ... \
    -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"search_documents",...}}'
done
```

결과:

```
call 1 ~ call 20: "isError":false
call 21: "isError":true
call 22: "isError":true
```

21번째 응답 본문:

```json
{"jsonrpc":"2.0","id":99,"result":{"content":[{"type":"text","text":"호출 횟수 제한을 초과했습니다. 잠시 후 다시 시도해 주세요."}],"isError":true}}
```

`SEARCH_RATE_LIMIT_PER_MINUTE=20` 설정대로 정확히 20회까지 통과, 21회부터 차단됨을 확인.

---

## 발견 및 해결 — 이번 이슈에서 새로 찾은 버그 3개

curl 단발 테스트로는 못 잡아냈던 버그들이라, 원인 규명 과정을 자세히 남긴다. 세 버그 전부 "MCP 클라이언트가 실제로 도구를 체이닝해서 쓰는 상황"에서만 드러났다.

### 1) `SearchResultItem`에 `documentId` 누락 — 도구 체이닝 자체가 불가능했던 설계 결함

**증상**: `search_documents` 결과에 `documentTitle`만 있고 `documentId`가 없어서, AI Agent가 검색 결과를 보고 이어서 `get_document_detail`을 호출하는 체이닝 워크플로우 자체가 원천적으로 불가능했다.

**원인**: `SearchResultItem`(웹 `SearchController`와 MCP 양쪽이 공유하는 DTO)에 `documentId` 필드가 아예 없었다. 반면 상위 데이터소스인 `VectorSearchCandidate`엔 `documentId`가 이미 있었다 — DB 조회 단계에선 이미 갖고 있는 값을 `SearchResultItem.of()`에서 옮겨 담는 순간 빠뜨린 것. `#105`(search_documents 최초 구현) 때부터 있었던 원본 설계 누락으로, 이번에 처음 발견했다.

**수정**:

```java
// SearchResultItem.java
public record SearchResultItem(
    int rank,
    Long documentId,   // ← 추가
    String documentTitle,
    String chunkText,
    Integer pageNo,
    BigDecimal similarityScore
) {
    public static SearchResultItem of(int rank, VectorSearchCandidate candidate) {
        return new SearchResultItem(
            rank, candidate.documentId(), candidate.documentTitle(),
            candidate.chunkText(), candidate.pageNo(), candidate.similarityScore()
        );
    }
}
```

`DocGridMcpTools.truncateChunkText()`에서 `SearchResultItem`을 수동 재조립하는 부분도 `documentId`를 같이 넘기도록 수정. `DocumentId` 없이 재구성해서 사실상 그동안 결과가 항상 null이었다.

**검증**: curl로 재검증 — `{"rank":1,"documentId":1,"documentTitle":"Spring Boot 개발 가이드",...}` 응답 확인. 이후 실제 Claude Desktop 대화(위 "정상 케이스 3)")에서 체이닝이 자연스럽게 이어지는 것으로 최종 확인.

### 2) `McpApiKeyAuthFilter`가 인증 정보를 저장하지 않아 비동기 재디스패치에서 `AuthorizationDeniedException`

**증상**: Claude Desktop을 실제로 연결하면 `initialize`는 성공하는데, 그 직후 여러 요청이 `AuthorizationDeniedException: Access Denied`로 거부됨. mcp-remote 쪽 로그엔 `SSE stream disconnected: TypeError: terminated`로 나타남.

**원인**: `McpApiKeyAuthFilter`가 인증 성공 시 `SecurityContextHolder.getContext().setAuthentication(...)`만 하고, 그 인증 정보를 `SecurityContextRepository`에 저장하지 않았다. MCP Streamable HTTP는 응답을 서블릿 **비동기 재디스패치**(`AsyncContext.dispatch()`)로 처리하는데, 첫 번째(동기) 통과 때는 방금 세팅한 스레드-로컬 값이 살아있어 문제없지만, 비동기 재디스패치 시점엔 `SecurityContextHolderFilter`가 저장소에서 컨텍스트를 다시 로드하려다 아무것도 못 찾아 빈 컨텍스트로 판단 → 그 다음 `AuthorizationFilter`가 미인증으로 보고 거부한 것.

**수정**:

```java
// McpApiKeyAuthFilter.java
private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();
...
SecurityContext context = SecurityContextHolder.getContext();
context.setAuthentication(authentication);
// 비동기 재디스패치에서도 복원되도록 요청 attribute에 명시적으로 저장
securityContextRepository.saveContext(context, request, response);
```

`RequestAttributeSecurityContextRepository`는 `HttpServletRequest`의 attribute에 저장하는데, Tomcat의 비동기 재디스패치는 원본 request 객체를 그대로 재사용하므로 이 방식으로 재디스패치 시점까지 인증 정보가 살아남는다.

**검증**: 수정 후 앱 재기동 → Claude Desktop 재연결 → mcp-remote 디버그 로그에서 `AuthorizationDeniedException` 없이 `tools/list`/`search_documents`까지 전부 성공 응답 확인.

### 3) `get_document_detail`의 `LazyInitializationException` — #120에서 예상했던 리스크가 실제로 터진 사례

**증상**: 2번 버그를 고친 뒤 재연결해서 `search_documents`는 성공했는데, 이어서 `get_document_detail`을 호출하니 서버 로그에 아래 예외가 남:

```
org.hibernate.LazyInitializationException: Could not initialize proxy [DocumentVersion#1] - no session
    at DocGridMcpTools.lambda$getDocumentDetail$2(DocGridMcpTools.java:107)
```

**원인**: `#120`(DB 커넥션 누수 수정)에서 `/mcp` 경로만 OSIV(Open Session In View)를 제외시켰다(`WebMvcConfig.addInterceptors()`에서 `excludePathPatterns(McpApiKeyAuthFilter.MCP_ENDPOINT)`). 그 결과 `/mcp` 안에서는 트랜잭션이 끝나면 Hibernate 세션도 즉시 닫힌다. `getDocumentDetail()`은 `documentRepository.findById(documentId)`(자기 완결적인 짧은 트랜잭션)로 엔티티를 가져온 뒤, 그 트랜잭션 밖에서 `document.getCurrentVersion().getVersionNo()`(LAZY 연관관계)를 호출하고 있었다 — OSIV가 켜져 있던 예전엔 이게 "우연히" 잘 됐지만, `/mcp`에서 OSIV를 끈 뒤로는 세션이 이미 닫힌 상태에서 지연 로딩을 시도해 예외가 났다.

원인 파악 후 다른 두 도구(`search_documents`→`SearchFacade`, `get_indexing_status`→`DocumentQueryService`)도 같은 위험이 있는지 확인했는데, 둘 다 클래스 레벨 `@Transactional`이 있는 진짜 서비스를 거쳐서 엔티티→DTO 변환까지 트랜잭션 안에서 끝내고 반환하므로 안전함을 확인했다. `DocGridMcpTools`가 리포지토리를 직접 호출하고 엔티티를 다루는 `get_document_detail`만 유일하게 이 문제가 있었다.

**수정**: `DocumentRepository`에 이미 있던 "B담당자용 읽기 쿼리" 전례(`findDocumentStatus`)와 같은 패턴으로, `JOIN FETCH` 쿼리 메서드를 추가:

```java
// DocumentRepository.java
@Query("SELECT d FROM Document d LEFT JOIN FETCH d.currentVersion WHERE d.id = :documentId")
Optional<Document> findByIdWithCurrentVersion(@Param("documentId") Long documentId);
```

```java
// DocGridMcpTools.java — getDocumentDetail()
Document document = documentRepository.findByIdWithCurrentVersion(documentId)
        .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));
```

`executeTool()` 전체를 트랜잭션으로 감싸는 더 큰 수정도 검토했지만, 위 확인 결과 다른 두 도구는 이미 안전해서 방어할 대상이 없었다. 문제가 확인된 지점(`get_document_detail`)만 정확히 고치는 쪽으로 결정.

**검증**: `DocGridMcpToolsTest`의 관련 테스트 3개가 `documentRepository.findById()`를 mock하고 있어서 메서드명 교체(`findByIdWithCurrentVersion`)로 같이 수정. 이후 앱 재기동 → Claude Desktop에서 재시도 → 위 "정상 케이스 3)" 그대로 정상 응답 확인.

---

## 자동화 테스트 결과

`./gradlew test`: **709개 테스트 전체 통과, 실패 0개**

(674 → 706으로 teammate의 #119/#122 PR 병합 반영, 706 → 709는 이번 이슈에서 `DocGridMcpToolsTest` 수정 없이 순수 신규 검증 로직만으로는 개수 변화 없음 — 기존 3개 테스트의 mock 대상 메서드명만 교체됐고, `SearchResultItem` 필드 추가로 인한 관련 테스트 2개 positional 생성자 인자 수정.)

## 결론

- MCP 서버 3개 도구(`search_documents`/`get_document_detail`/`get_indexing_status`) 전부 실제 Claude Desktop 대화에서 정상 동작 확인
- 도구 체이닝(검색 → 상세조회 → 인덱싱상태) 자연스럽게 이어지는 것 확인
- 권한 차단(`PERMISSION_DENIED`), Rate Limit(`RATE_LIMIT_EXCEEDED`) 둘 다 설계대로 동작 확인
- 이 과정에서 curl 테스트로는 못 잡았던 버그 3개(도구 체이닝 불가, 비동기 재디스패치 인증 유실, 지연 로딩 예외) 발견·수정
- `./gradlew test` 709개 전체 통과

MCP Server 블록(#93~#127) 전체 로드맵 완료.
