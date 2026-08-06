# #113 get_document_detail + get_indexing_status 도구 구현 (F-MCP-03/04)

closes #113

---

## 배경

MCP Server 블록의 네 번째 이슈. Issue 1(#93)에서 등록만 해둔 빈 핸들러 3개 중 나머지 2개(`get_document_detail`, `get_indexing_status`)를 실제 로직으로 채운다.

- **`get_document_detail`**: "이 문서가 뭔지" — 제목, 현재 버전 번호, 상태, 마지막 수정 시각 같은 메타데이터 조회
- **`get_indexing_status`**: "이 문서가 지금 검색 가능한 상태로 준비됐는지" — PENDING/PROCESSING/INDEXED/FAILED 파이프라인 진행 상황 조회

두 도구 모두 새 검색/권한 로직을 만들지 않고, 기존 `PermissionQueryService`(B담당자)와 `Document` 엔티티·`DocumentQueryService`(A담당자, 읽기 전용 참조)를 그대로 활용한다.

---

## 착수 전 확정한 설계 결정 3가지

구현을 시작하기 전에 이슈 To-do 원안에서 실제로 바뀐 부분들을 먼저 정리한다.

### 1) 새 서비스 클래스를 만들지 않는다

원래 `McpDocumentQueryService` 같은 별도 서비스를 새로 만드는 방향을 검토했으나, 필요한 로직 양(권한체크 한 줄 + `findById` 한 줄 + 필드 몇 개 추출, 또는 기존 서비스 그대로 호출)이 서비스 클래스를 뺄 만큼 크지 않다고 판단해 취소했다. Issue 3(`search_documents`)에서 이미 "완성된 서비스는 위임 호출, MCP 어댑터 전용 글루 코드는 `DocGridMcpTools` 안에 private 메서드로"라는 패턴을 세워뒀는데, 여기서만 다르게 가면 일관성이 깨진다. 두 도구 모두 `DocGridMcpTools`에 직접 구현했다.

### 2) A담당자 소유 파일은 수정하지 않는다

`DocumentQueryService.getDocumentStatus()`는 title/updatedAt을 반환하지 않는다. 처음엔 "서비스 확장이 필요할 수도"라고 봤으나, `Document` 엔티티 자체에 `title`/`status`/`currentVersion`/`updatedAt`(BaseEntity)이 이미 다 있다는 걸 확인하고, `DocumentRepository.findById()`로 엔티티를 **직접** 읽어서 해결했다. `DocumentQueryService`/`Document` 엔티티는 한 글자도 안 고쳤다 — MCP 블록이 "documents 테이블을 읽기 전용으로 참조한다"는 원래 인터페이스 계약 그대로다.

`get_indexing_status`는 반대로 `DocumentQueryService.getDocumentStatus(userId, documentId)`가 이미 정확히 필요한 걸 반환하고 있어서, 이 메서드는 **그대로(수정 없이)** 재사용했다.

### 3) `version_id` 단독 조회는 범위에서 제외한다

명세서 원안(F-MCP-04)엔 `document_id` 또는 `version_id`로 조회 가능하다고 돼 있었다. 하지만 `version_id`만으로 상태를 조회하려면 A담당자 쪽에 새 조회 로직이 필요해지는데, AI Agent가 `search_documents`/`get_document_detail` 없이 `version_id`만 아는 상황이 현실적으로 드물어 비용 대비 가치가 낮다고 판단했다. `documentId`를 두 도구 모두 필수 파라미터로 뒀다.

---

## 신규/변경 파일

### DocumentDetailResponse.java (신규)

```java
public record DocumentDetailResponse(
        Long documentId,
        String title,
        Integer currentVersionNo,   // 아직 확정된 버전이 없으면 null
        DocumentStatus status,
        LocalDateTime updatedAt
) {
}
```

기존 `document` 도메인 DTO들(`DocumentStatusResponse`, `DocumentUploadResponse` 등)을 전부 확인했지만 이 조합(title+currentVersionNo+status+updatedAt)과 일치하는 게 없어 새로 만들었다.

**패키지 위치**: `domain/mcp/dto/response`. `Document` 엔티티는 `document` 도메인 소속이지만, 이 코드베이스의 DTO 배치 관례는 "필드가 어느 엔티티 소속이냐"가 아니라 "어느 도메인이 이 모양을 쓰느냐"다. 실제 선례 두 가지로 확인했다:
- `search` 도메인의 `SearchResultItem`(document/embedding 데이터를 담지만 `domain/search/dto/response`에 위치)
- Issue 2의 `McpAccessTokenResponse`(엔티티는 `domain/user/entity`에 있지만 DTO는 `domain/mcp/dto/response`에 위치)

### DocGridMcpTools.java — 두 메서드 구현

```java
@McpTool(name = "get_document_detail", ...)
public String getDocumentDetail(
        @McpToolParam(description = "문서 ID", required = true) Long documentId) {
    // 1. documentId 필수 확인 (SDK가 required를 강제하지 않으므로 직접 검증)
    requireDocumentId(documentId);

    // 2. McpApiKeyAuthFilter가 SecurityContext에 저장해둔 사용자 식별
    Long userId = currentUserId();

    // 3. 권한 확인 — false면 문서 존재 여부를 노출하지 않기 위해 조회 전에 차단
    if (!permissionQueryService.canReadDocument(userId, documentId)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }

    // 4. 문서 조회 — title/status/currentVersion/updatedAt은 Document 엔티티에 이미 있어 직접 사용
    Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

    Integer currentVersionNo = document.getCurrentVersion() != null
            ? document.getCurrentVersion().getVersionNo()
            : null;
    DocumentDetailResponse response = new DocumentDetailResponse(
            document.getId(), document.getTitle(), currentVersionNo,
            document.getStatus(), document.getUpdatedAt()
    );

    // 5. JSON으로 직렬화
    return toJson(response);
}

@McpTool(name = "get_indexing_status", ...)
public String getIndexingStatus(
        @McpToolParam(description = "문서 ID", required = true) Long documentId) {
    // 1. documentId 필수 확인
    requireDocumentId(documentId);
    // 2. 사용자 식별
    Long userId = currentUserId();
    // 3. 상태 조회 — DocumentQueryService.getDocumentStatus가 내부에서 권한체크까지 수행 (그대로 재사용)
    return toJson(documentQueryService.getDocumentStatus(userId, documentId));
}
```

`getDocumentDetail`에서 권한체크(3단계)를 먼저 하고 조회(4단계)를 나중에 하는 순서는 의도적이다 — 문서가 존재하지 않는지, 권한이 없는지를 클라이언트에게 구분해서 알려주되, `canReadDocument()`가 내부적으로 문서 존재 여부를 이미 확인(없으면 `DOCUMENT_NOT_FOUND`를 스스로 던짐)하므로, 뒤이은 `documentRepository.findById()`의 `DOCUMENT_NOT_FOUND` 처리는 이론상 도달하지 않는 방어 코드다(예: 두 호출 사이 문서가 삭제되는 경쟁 상황 대비).

**직렬화가 왜 필요한가**: MCP 응답 콘텐츠는 텍스트(`String`)라, 구조화된 객체(`DocumentDetailResponse`, `DocumentStatusResponse`)를 그대로 못 돌려준다. JSON으로 바꾸면 필드 경계가 명확하게 유지되고(문장으로 풀면 파싱이 애매해짐), LLM이 JSON 구조를 매우 잘 파싱하기 때문에 표준적인 선택이다.

---

## API 동작 확인 (실제 curl 검증)

로컬 DB를 다시 확인해보니 이전 RAG e2e 테스트(#78) 때 쓰던 documentId=3,4가 아니라 지금은 **1,2**로 바뀌어 있었다(그 사이 로컬 DB가 리셋된 것으로 보임). documentId=1로 재검증했다.

### get_document_detail

```json
// 요청
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_document_detail","arguments":{"documentId":1}}}

// 정상 응답
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"{\"documentId\":1,\"title\":\"Spring Boot 개발 가이드\",\"currentVersionNo\":1,\"status\":\"INDEXED\",\"updatedAt\":\"2026-08-05T21:33:29.678418\"}"}],"isError":false}}
```

### get_indexing_status

```json
// 요청
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_indexing_status","arguments":{"documentId":1}}}

// 정상 응답
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"{\"documentId\":1,\"documentStatus\":\"INDEXED\",\"currentVersion\":{\"versionNo\":1,\"status\":\"INDEXED\"},\"processingVersion\":null}"}],"isError":false}}
```

### 에러 케이스

| 상황 | 도구 | 응답 |
| --- | --- | --- |
| documentId 누락 | 둘 다 | `isError:true`, `"documentId는 필수입니다."` |
| 존재하지 않는 문서(999999) | 둘 다 | `isError:true`, `"문서를 찾을 수 없습니다."` |
| 인증 없이 호출 | 둘 다 | HTTP **403** (앱 전체 기존 동작, Issue 2/3 설계 문서에 이미 기록) |
| 권한 없음 | 둘 다 | `isError:true`, `"접근 권한이 없습니다."` (로컬 시드 데이터에 다른 사용자 소유 문서가 없어 curl로는 직접 재현 못함 — 단위 테스트로 커버) |

---

## 검증 중 발견한 두 가지 (둘 다 실제 앱 버그 아님)

### 1) 테스트 스크립트 반복 실행으로 DB 커넥션 풀 고갈

`get_indexing_status`를 테스트하던 중 응답이 없이 멈추는 현상이 있었다. 로그를 보니 `HikariPool ... active=5, idle=0` — DB 커넥션 풀(최대 5개)이 고갈된 상태였다. 원인은 curl 테스트 스크립트를 여러 번 연달아 실행하면서 매번 새 MCP 세션(`initialize`)을 열고 명시적으로 안 닫았기 때문으로 보인다. 서버를 재시작하고 세션 하나를 계속 재사용해 순차 호출하니 정상 동작했다.

**앱 버그가 아니라 테스트 방식 문제**로 판단했다 — Claude Desktop처럼 세션 하나를 계속 재사용하는 정상 사용 패턴에서는 문제가 안 될 것으로 보이지만, Streamable HTTP 세션이 명시적으로 종료되지 않으면 리소스를 계속 점유하는 것으로 보이는 정황이라, Issue 6(Claude Desktop 실연동 검증)에서 세션 생명주기를 한 번 더 살펴볼 필요가 있다.

### 2) 단위 테스트용 ObjectMapper에 JavaTimeModule 누락

`DocGridMcpToolsTest`에서 `new ObjectMapper()`(기본 설정)를 썼는데, `DocumentDetailResponse.updatedAt`(`LocalDateTime`) 직렬화 시 `InvalidDefinitionException`이 발생했다. 실제 앱의 Spring 관리 `ObjectMapper` 빈은 `jackson-datatype-jsr310`이 자동 등록돼 있어 문제없이 동작했지만(curl 테스트로 확인됨), 테스트에서 수동으로 만든 `ObjectMapper`엔 이 모듈이 없었다. `objectMapper.registerModule(new JavaTimeModule())`을 추가해 해결했다 — **테스트 코드만의 문제였고 앱 동작에는 영향 없음**.

---

## 검증

- 신규 단위 테스트 8개(`DocGridMcpToolsTest`에 통합): 정상 케이스 2개(`get_document_detail`, `get_indexing_status`), `documentId` 누락 2개, 권한없음 1개, 문서없음 1개, 미인증 2개
- 실제 서버 기동 후 curl로 로그인 → API 키 발급 → MCP 핸드셰이크 → `tools/call` 전체 플로우 검증 (위 표 전부, 권한없음 제외)
- `./gradlew test`: **633개 테스트 전체 통과, 실패 0개**

---

## 남은 이슈 / TODO

- Rate Limiting, 출력 정제 — 별도 이슈(F-MCP-08/09), 도구 3종이 모두 완성된 지금부터 착수 가능
- Streamable HTTP 세션이 명시적으로 종료 안 될 때 리소스(DB 커넥션 등)를 계속 점유하는지 여부 — Claude Desktop 연동 검증 이슈에서 실사용 시나리오로 재확인

### 다음 단계

도구 3종(`search_documents`, `get_document_detail`, `get_indexing_status`) 구현이 모두 끝났다. 다음은 Rate Limiting + 출력 정제(F-MCP-08/09) 이슈, 그다음이 Claude Desktop 실연동 검증(Issue 6, 테스트 이슈로 분리)이다.
