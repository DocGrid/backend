# #24 내 문서 권한 확인 API — GET /permissions/documents/{documentId}/me

closes #24

---

## 배경

`#21`의 `canReadDocument`/`canWriteDocument`/`canAdminDocument`는 각각 "이거 하나 되나요?"를 boolean으로만 답한다. 근데 실제로 프론트엔드가 "이 문서에 대해 내가 뭘 할 수 있는지"를 화면에 보여주려면, read/write/admin을 **한 번에** 알아야 하고, 가능하면 "왜 되는지(어떤 권한 경로로)"도 같이 알고 싶다. 이 이슈는 그 용도의 API 하나를 만든다.

---

## boolean 메서드와의 차이 — 왜 새 메서드가 필요했나

```text
canReadDocument()  → 읽기 권한 여부만, 첫 true에서 즉시 반환(단락 평가)
canWriteDocument() → 쓰기 권한 여부만, 첫 true에서 즉시 반환
canAdminDocument() → 관리 권한 여부만, 첫 true에서 즉시 반환
```
이 셋을 그냥 세 번 호출하면 될 것 같지만 안 된다 — **단락 평가가 서로 다른 결과를 감춘다.** 예를 들어 캐시(3단계)에서 `canRead=true`가 나와서 `canReadDocument()`가 거기서 멈춰버리면, 그 아래 ROLE 단계(4단계)에 `canWrite=true`가 있어도 그건 절대 확인되지 않는다(단락 평가는 애초에 "이후 단계를 볼 필요 없음"을 전제로 하니까). 그래서 `checkDocumentPermission()`은 **OWNER가 아닌 이상 단락 평가를 하지 않고 5단계를 전부 끝까지 탐색**한다.

```text
checkDocumentPermission()
→ 모든 단계를 끝까지 탐색 (OWNER만 예외)
→ 권한을 준 경로(sources)를 전부 수집
→ canRead / canWrite / canAdmin 동시 반환
```

---

## `PermissionSourceType` — 권한이 어디서 왔는지 나타내는 enum

```java
public enum PermissionSourceType {
    OWNER,
    PUBLIC,
    USER_CACHE,
    ROLE,
    DEPARTMENT
}
```
`#18`의 `AccessSourceType`(캐시 테이블 내부 저장용, `OWNER`/`DIRECT_DOCUMENT_PERMISSION`/`DIRECT_COLLECTION_PERMISSION`)과 이름이 비슷해서 헷갈리기 쉽지만 **완전히 다른 enum**이다. `PermissionSourceType`은 이 API 응답에만 쓰이는, "사용자에게 보여줄 경로 이름"이다. 예를 들어 `AccessSourceType.DIRECT_DOCUMENT_PERMISSION`이든 `DIRECT_COLLECTION_PERMISSION`이든, 사용자 응답에서는 둘 다 그냥 `USER_CACHE`로 뭉뚱그려 보여준다 — 사용자 입장에서는 "캐시로 잡혔다"는 사실만 중요하지 문서 직접 권한이었는지 컬렉션 경유였는지는 API 소비자에게 노출할 필요가 없다고 판단한 것으로 보인다.

---

## 신규 파일

### `domain/permission/service/query/PermissionQueryService.java` — `checkDocumentPermission()` 추가

```java
public DocumentPermissionSummaryResponse checkDocumentPermission(Long userId, Long documentId) {
    Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

    List<PermissionSourceType> sources = new ArrayList<>();
    boolean canRead = false, canWrite = false, canAdmin = false;

    // 1단계: 소유자 — 유일하게 즉시 반환
    if (document.getOwner().getId().equals(userId)) {
        sources.add(PermissionSourceType.OWNER);
        return new DocumentPermissionSummaryResponse(documentId, true, true, true, sources);
    }

    // 2단계: PUBLIC — 읽기만, 단락 없이 계속
    if (document.getVisibility() == VisibilityType.PUBLIC) {
        canRead = true;
        sources.add(PermissionSourceType.PUBLIC);
    }

    // 3단계: USER 캐시 — read/write/admin 각각 확인
    boolean cacheRead  = cacheRepository.existsValidReadCache(userId, documentId);
    boolean cacheWrite = cacheRepository.existsValidWriteCache(userId, documentId);
    boolean cacheAdmin = cacheRepository.existsValidAdminCache(userId, documentId);
    if (cacheRead || cacheWrite || cacheAdmin) {
        sources.add(PermissionSourceType.USER_CACHE);
        if (cacheRead)  canRead  = true;
        if (cacheWrite) canWrite = true;
        if (cacheAdmin) canAdmin = true;
    }

    // 4단계: ROLE live — read/write/admin 각각 확인 (문서 직접 + 컬렉션 경유 OR)
    boolean roleRead  = documentPermissionRepository.existsRoleReadPermission(userId, documentId)
            || collectionPermissionRepository.existsRoleReadPermissionForDocument(userId, documentId);
    // ... roleWrite, roleAdmin도 동일 패턴
    if (roleRead || roleWrite || roleAdmin) {
        sources.add(PermissionSourceType.ROLE);
        if (roleRead)  canRead  = true;
        if (roleWrite) canWrite = true;
        if (roleAdmin) canAdmin = true;
    }

    // 5단계: DEPARTMENT live — 4단계와 동일 패턴

    return new DocumentPermissionSummaryResponse(documentId, canRead, canWrite, canAdmin, sources);
}
```

`#21`의 `canReadDocument()`가 "찾으면 바로 return"이었다면, 이 메서드는 각 단계에서 `boolean` 지역변수(`cacheRead`, `roleRead` 등)에 **결과를 담아두고 계속 진행**한다. 그리고 `canRead`/`canWrite`/`canAdmin`은 한 번 `true`가 되면 이후 단계에서 다시 `false`로 덮어써지지 않는다(`if (xxxRead) canRead = true;`만 있고 `else canRead = false`가 없다) — 여러 단계에서 얻은 권한이 **누적**된다.

### `domain/permission/dto/response/DocumentPermissionSummaryResponse.java`

```java
public record DocumentPermissionSummaryResponse(
        Long documentId,
        boolean canRead,
        boolean canWrite,
        boolean canAdmin,
        List<PermissionSourceType> sources
) {}
```

### `domain/permission/controller/PermissionController.java` — `GET /permissions/documents/{documentId}/me`

```java
@GetMapping("/documents/{documentId}/me")
public ResponseEntity<ApiResponse<DocumentPermissionSummaryResponse>> getMyDocumentPermission(
        @PathVariable Long documentId,
        @Parameter(hidden = true) @CurrentUser Long userId) {
    return ResponseUtils.ok(permissionQueryService.checkDocumentPermission(userId, documentId));
}
```
`@CurrentUser`로 토큰에서 뽑은 사용자 ID를 그대로 쓴다 — 경로에 `userId`를 안 받고 `/me`로 고정한 이유는, "내 권한 확인"이 다른 사람의 권한을 조회하는 용도로 오용되지 않게 하기 위함이다(항상 요청자 본인 기준).

---

## 로컬 검증 (Swagger 수동 테스트 — 실제 수행 기록)

`docs/test-results/kangcheolung-#21-permission-query-service.md`에 이 API(`GET /permissions/documents/{id}/me`)로 5단계 전체가 실제 시나리오로 확인되어 있다. 발췌:

**OWNER (4.1절)**
```json
{ "documentId": 1, "canRead": true, "canWrite": true, "canAdmin": true, "sources": ["OWNER"] }
```

**PUBLIC — read만 true (4.2절)**
```json
{ "documentId": 2, "canRead": true, "canWrite": false, "canAdmin": false, "sources": ["PUBLIC"] }
```

**USER_CACHE + ROLE 동시 수집 (4.5절)** — 이 이슈에서 가장 중요하게 검증해야 하는 "누적" 동작
```json
{ "documentId": 1, "canRead": true, "canWrite": false, "canAdmin": false, "sources": ["USER_CACHE", "ROLE"] }
```
USER 직접 권한과 컬렉션 ROLE 권한이 **둘 다** 걸려있는 상태에서 `sources`에 두 값이 동시에 들어있음을 확인했다 — `checkDocumentPermission()`이 단락 평가 없이 끝까지 탐색한다는 설계가 실제로 동작함을 보여주는 핵심 근거다.

**권한 전부 소멸 → 빈 배열 (4.7절)**
```json
{ "documentId": 1, "canRead": false, "canWrite": false, "canAdmin": false, "sources": [] }
```
컬렉션에서 문서를 제거해 ROLE 경로가 끊긴 뒤 호출한 결과. 권한이 없는 게 에러가 아니라 정상 응답(200) + 빈 배열/전부 false로 표현됨을 확인.

### 자동 테스트

`checkDocumentPermission()`은 `PermissionQueryServiceTest`(`#21`)에 포함되어 함께 검증된다 — 별도 테스트 클래스로 분리되어 있지 않다.

```bash
$ ./gradlew test --tests "*PermissionQueryServiceTest*"
BUILD SUCCESSFUL
```

---

## 에러 케이스 정리

| 상황 | HTTP | 코드 |
|---|---:|---|
| 문서 없음 | 404 | `DOCUMENT-001` |

권한이 전혀 없어도 에러가 아니다. `canRead`/`canWrite`/`canAdmin` 전부 `false`, `sources: []`로 정상 200 응답한다 — RAG 블록의 NO_CONTEXT 처리("검색 결과 0건은 에러가 아니라 정상 케이스")와 같은 철학이다.

---

## 설계 결정 요약

**단락 평가를 의도적으로 포기**: `#21`의 다른 4개 메서드는 전부 단락 평가로 성능을 최적화했는데, 이 메서드만 예외적으로 전체 탐색을 한다. "빠른 boolean 판단"과 "권한의 전체 그림을 보여주는 요약"은 목적이 달라서, 성능보다 정확한 정보 수집을 우선한 것이다. OWNER만은 예외로 즉시 반환하는데, 소유자는 어차피 모든 권한(`true`/`true`/`true`)이 확정이라 나머지 단계를 탐색해도 결과가 달라질 수 없기 때문이다(순수 성능 최적화, 정보 손실 없음).

**`AccessSourceType`(캐시 내부용)과 `PermissionSourceType`(API 응답용)을 별도 enum으로 분리**: 캐시 테이블은 "문서 직접 권한이었는지 컬렉션 경유였는지"까지 구분해서 저장해야(무효화 시 정확한 범위로 처리해야 하니까) 하지만, API 응답에서는 사용자가 이 구분을 알 필요가 없다. 하나의 enum으로 억지로 합쳤으면 API 응답에 캐시 내부 구현 세부사항이 새어나갔을 것이다.

**컬렉션 판단이 아니라 문서 판단 API로 설계**: "내 컬렉션 권한 확인" API는 따로 없다. 문서 판단에는 이미 컬렉션 경유 권한까지 다 포함해서(`existsRoleReadPermissionForDocument()` 등) 계산하므로, 실질적으로 컬렉션 권한도 문서를 통해 간접 확인 가능하다.

---

## 남은 이슈 / TODO

- `checkDocumentPermission()`이 `canReadDocument()` 등과 별개로 캐시/ROLE/DEPT 쿼리를 다시 전부 실행한다 — 두 메서드가 같은 문서에 대해 동시에 호출될 일은 지금 없지만, 쿼리 로직 자체가 중복되어 있어(`#21`의 TODO와 동일한 지점) 유지보수 시 두 곳을 같이 고쳐야 하는 부담이 있다.

## 다음 단계

`#29`(컬렉션 관리 API)로 이어진다. 권한 블록(`#16`~`#29`) 전체가 완료된 뒤 RAG 블록(F-SEARCH, F-RAG)이 `PermissionQueryService.canReadDocument()`를 재사용해서 검색 권한 필터링을 구현한다.
