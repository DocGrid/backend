# #29 컬렉션 삭제 / 문서 제거 API

closes #29

---

## 배경

`#16`이 컬렉션 생성·문서 추가를 만들었다면, 이 이슈는 그 반대 방향(목록 조회/삭제/제거)을 만든다. 단순히 "row 지우기"가 아닌 이유는, 컬렉션에는 `#18`에서 만든 권한(`CollectionPermission`)과 캐시(`UserDocumentAccessCache`)가 딸려 있기 때문이다 — 컬렉션을 지우거나 컬렉션에서 문서를 빼면, 그 컬렉션을 통해 파생된 권한/캐시도 같이 정리되어야 실제로 접근이 막힌다. 이 정리를 빠뜨리면 컬렉션은 없어졌는데 캐시에는 여전히 "읽기 가능"이 남아있는 유령 권한이 생긴다.

---

## 전체 흐름

```text
GET /collections                              → 내 컬렉션 목록 (ACTIVE만)
                                                 (2026-08-18, 이슈 #229부터: owner+PUBLIC+권한부여+상속 전체,
                                                  페이지네이션·keyword 검색 포함 — 아래 "이후 업데이트" 참고)
DELETE /collections/{id}                       → 컬렉션 소유자만, 권한 전부 정리 후 소프트 삭제
                                                 (2026-08-18부터: 하위 컬렉션 전체까지 cascade)
DELETE /collections/{id}/documents/{docId}     → 컬렉션 소유자만, 그 문서에 대한 캐시만 정리 후 링크 삭제
```

두 삭제 API 모두 **소유자 전용**이다. `#18`의 권한 부여/회수는 `canWriteCollection()`/`canAdminCollection()`처럼 권한 보유자면 위임 수행이 가능한데, 이 두 API는 그거와 다르게 `collection.getOwner().getId().equals(userId)`로 못박혀 있다 — 컬렉션 자체를 없애거나 문서를 빼는 건 위임 가능한 관리 작업이 아니라 소유권 그 자체의 행사로 설계했기 때문이다.

---

## 신규 파일

### `domain/collection/controller/CollectionController.java`

```java
@GetMapping
public ResponseEntity<ApiResponse<List<CollectionResponse>>> getMyCollections(
        @Parameter(hidden = true) @CurrentUser Long userId) {
    return ResponseUtils.ok(collectionQueryService.getMyCollections(userId));
}

@DeleteMapping("/{collectionId}")
public ResponseEntity<ApiResponse<Void>> deleteCollection(
        @PathVariable Long collectionId,
        @Parameter(hidden = true) @CurrentUser Long userId) {
    collectionCommandService.deleteCollection(collectionId, userId);
    return ResponseUtils.noContent();
}

@DeleteMapping("/{collectionId}/documents/{documentId}")
public ResponseEntity<ApiResponse<Void>> removeDocument(
        @PathVariable Long collectionId,
        @PathVariable Long documentId,
        @Parameter(hidden = true) @CurrentUser Long userId) {
    collectionCommandService.removeDocument(collectionId, documentId, userId);
    return ResponseUtils.noContent();
}
```
삭제·제거 두 엔드포인트는 `ResponseUtils.ok()`가 아니라 `ResponseUtils.noContent()`(204)를 쓴다 — 생성/조회 계열(`createCollection`은 201, `getCollection`은 200)과 다르게, 삭제 성공은 돌려줄 본문이 없다는 걸 명시적으로 드러낸다.

### `domain/collection/service/query/CollectionQueryService.java` — `getMyCollections()`

**(2026-08-18, 이슈 #229 관련 작업으로 이 메서드는 `getCollections(userId, keyword, page, size)`로 완전히 교체됨. 아래는 작성 당시 원본 코드 — "이후 업데이트" 절 참고.)**

```java
// 내 컬렉션 목록 조회 (ACTIVE 상태만)
public List<CollectionResponse> getMyCollections(Long userId) {
    return collectionRepository.findAllByOwnerIdAndStatus(userId, CollectionStatus.ACTIVE)
            .stream()
            .map(collectionConverter::toResponse)
            .toList();
}
```
`#16`에 이미 있는 `getCollection(id)`(단건 조회, 권한 체크가 없다는 게 이미 TODO로 기록됨)와 다르게, 이건 `ownerId` 기준으로 리포지토리 쿼리 자체가 필터링한다 — "내가 만든 컬렉션 목록"이라 소유자 필터가 곧 접근 제어라서 서비스 레이어에 별도 권한 체크 코드가 필요 없다(작성 당시 기준. 지금은 owner 외에도 권한부여자를 포함하므로 이 필터링 논리 자체가 바뀌었다).

### `domain/collection/service/command/CollectionCommandService.java` — `deleteCollection()`

**(2026-08-18, 이슈 #229부터 하위 컬렉션 전체까지 cascade하도록 확장됨. 아래는 작성 당시 원본 — "이후 업데이트" 절 참고.)**

```java
// 컬렉션 soft delete — 소유자만 가능
public void deleteCollection(Long collectionId, Long userId) {
    DocumentCollection collection = collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

    if (!collection.getOwner().getId().equals(userId)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }

    List<CollectionPermission> permissions = collectionPermissionRepository.findAllByCollectionId(collectionId);
    permissions.stream()
            .filter(p -> p.getTargetType() == PermissionTargetType.USER)
            .forEach(p -> cacheService.bulkRevokeBySource(AccessSourceType.DIRECT_COLLECTION_PERMISSION, p.getId()));
    collectionPermissionRepository.deleteAll(permissions);

    collection.markDeleted(LocalDateTime.now());
}
```
순서가 중요하다 — ① 캐시 무효화(`forEach`) → ② 권한 레코드 삭제(`deleteAll`) → ③ 컬렉션 소프트 삭제(`markDeleted`). 반대로 권한 레코드를 먼저 지워버리면 `p.getId()`로 캐시를 찾아 무효화할 근거(`DIRECT_COLLECTION_PERMISSION` + `sourceId`)가 사라진다. ROLE/DEPARTMENT 대상 권한은 캐시에 애초에 안 들어가 있으므로(`#18`의 비대칭 캐싱 설계) `filter(USER)`로 걸러지고, `deleteAll()`에서는 캐시 무효화 없이 같이 삭제되는 것만으로 충분하다. 이 순서 원칙(캐시 무효화 → 권한 삭제 → soft delete)은 cascade로 확장된 뒤에도 그대로 유지된다.

### `domain/collection/service/command/CollectionCommandService.java` — `removeDocument()`

```java
// 컬렉션에서 문서 제거 — 소유자만 가능
public void removeDocument(Long collectionId, Long documentId, Long userId) {
    DocumentCollection collection = collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

    if (!collection.getOwner().getId().equals(userId)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }

    CollectionDocument collectionDocument = collectionDocumentRepository
            .findByCollectionIdAndDocumentId(collectionId, documentId)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_DOCUMENT_NOT_FOUND));

    List<Long> userPermissionIds = collectionPermissionRepository.findAllByCollectionId(collectionId)
            .stream()
            .filter(p -> p.getTargetType() == PermissionTargetType.USER)
            .map(CollectionPermission::getId)
            .toList();

    cacheService.bulkRevokeBySourcesForDocument(
            AccessSourceType.DIRECT_COLLECTION_PERMISSION, userPermissionIds, documentId);

    collectionDocumentRepository.delete(collectionDocument);
}
```
`deleteCollection()`과 결정적으로 다른 지점: 여기서는 **컬렉션 권한 자체는 그대로 둔다.** 컬렉션에서 문서 하나만 빠지는 거지 컬렉션이 없어지는 게 아니므로, 그 컬렉션에 남아있는 다른 문서들에 대한 권한/캐시는 건드리면 안 된다. 그래서 `bulkRevokeBySource`(컬렉션 전체 무효화)가 아니라 `bulkRevokeBySourcesForDocument`(그 문서 하나로 범위를 좁힌 무효화)를 쓴다. `removeDocument()`는 `userPermissionIds`가 비어있어도 그대로 호출하는데, 빈 리스트로 인한 불필요한 쿼리 실행은 `UserDocumentAccessCacheService.bulkRevokeBySourcesForDocument()` 내부의 `if (!sourceIds.isEmpty())` 가드가 서비스 레이어에서 막아준다(JPQL의 `IN` 절 자체가 빈 컬렉션을 안전하게 처리해주는 게 아니라, 호출 전에 명시적으로 걸러주는 것).

### `domain/permission/service/command/UserDocumentAccessCacheService.java` — 재사용

`#18`에서 이미 정의한 두 벌크 무효화 메서드를 그대로 재사용한다(이 이슈에서 신규 추가 없음):
```java
public void bulkRevokeBySource(AccessSourceType sourceType, Long sourceId) {
    cacheRepository.bulkInvalidateBySource(sourceType, sourceId);
}

public void bulkRevokeBySourcesForDocument(AccessSourceType sourceType, List<Long> sourceIds, Long documentId) {
    if (!sourceIds.isEmpty()) {
        cacheRepository.bulkInvalidateBySourceIdsAndDocument(sourceType, sourceIds, documentId);
    }
}
```
빈 리스트 가드(`if (!sourceIds.isEmpty())`)가 이 서비스 메서드 안에 있다는 게 핵심이다 — `removeDocument()`(커맨드 서비스)는 이 가드를 신경 쓰지 않고 그냥 호출하면 되고, 안전성은 이 서비스가 책임진다.

### `domain/permission/repository/UserDocumentAccessCacheRepository.java` — 재사용

두 벌크 쿼리 자체는 `#18`에서 정의됐다:
```java
@Modifying(clearAutomatically = true)
@Query("UPDATE UserDocumentAccessCache c SET c.invalidatedAt = CURRENT_TIMESTAMP " +
       "WHERE c.sourceType = :sourceType AND c.sourceId = :sourceId AND c.invalidatedAt IS NULL")
void bulkInvalidateBySource(AccessSourceType sourceType, Long sourceId);

@Modifying(clearAutomatically = true)
@Query("UPDATE UserDocumentAccessCache c SET c.invalidatedAt = CURRENT_TIMESTAMP " +
       "WHERE c.sourceType = :sourceType AND c.sourceId IN :sourceIds " +
       "AND c.document.id = :documentId AND c.invalidatedAt IS NULL")
void bulkInvalidateBySourceIdsAndDocument(AccessSourceType sourceType, List<Long> sourceIds, Long documentId);
```
`deleteCollection()`은 첫 번째(소스 단위 전체 무효화)를, `removeDocument()`는 두 번째(문서로 범위를 좁힌 무효화)를 쓴다.

---

## DB 변화 예시

컬렉션 3번에 문서 5·6·7번이 있고 유저 B에게 USER 대상 READ 권한(`permission_id=10`)이 있는 상태 기준.

**문서 5번 제거 후 (`DELETE /collections/3/documents/5`)**
```text
collection_documents:            문서 5번 행 삭제, 6·7번 행 유지
collection_permissions:          id=10 그대로 유지 (컬렉션 자체는 살아있으므로)
user_document_access_cache:      문서 5번 캐시만 invalidated_at 설정
                                  문서 6·7번 캐시는 그대로 유지
```

**컬렉션 삭제 후 (`DELETE /collections/3`)**
```text
document_collections:            status=DELETED, deleted_at=2026-xx-xx 12:00:00
collection_permissions:          id=10 행 자체가 삭제됨 (하드 삭제)
user_document_access_cache:      컬렉션 권한(id=10)에서 파생된 모든 행 invalidated_at 설정
```

---

## 로컬 검증 (Swagger 수동 테스트 — 실제 수행 기록)

`docs/test-results/kangcheolung-#21-permission-query-service.md`에서 발췌.

**4.7절 — 컬렉션에서 문서 제거 → 접근 차단(ROLE 경로 포함)**
1. 컬렉션 경유 ROLE 권한으로 문서 접근 가능한 상태(`sources: ["ROLE"]`) 확인
2. `DELETE /collections/{id}/documents/{documentId}` 호출 → `204 No Content`
3. 같은 사용자로 `GET /permissions/documents/{id}/me` 재호출
```json
{ "documentId": 1, "canRead": false, "canWrite": false, "canAdmin": false, "sources": [] }
```
컬렉션-문서 링크가 끊기니 ROLE 경로(컬렉션 경유 판단)도 같이 끊긴다는 것을 확인 — `ROLE`은 캐시가 아니라 항상 실시간(live) 조회이므로, `CollectionDocument`가 없어지면 그 즉시 다음 조회부터 반영된다(별도 무효화 처리 불필요, 조인 쿼리 자체가 결과를 안 준다).

**4.8절 — 컬렉션 소프트 삭제**
1. `DELETE /collections/{id}` 호출 → `204 No Content`
2. 직접 DB 확인:
```text
status=DELETED, deleted_at=2026-xx-xx xx:xx:xx
```
3. 해당 컬렉션의 `collection_permissions` 테이블 조회 → 0 rows (완전 삭제 확인)

**4.9절 — 삭제 후 목록 조회**
```text
GET /collections
→ 200 OK
[]
```
소프트 삭제된 컬렉션이 `getMyCollections()`(ACTIVE 필터)에서 제외됨을 확인. (당시 응답이 배열이었던 건 `getMyCollections()` 기준 — 2026-08-18 `getCollections()`로 교체된 뒤로는 `PageResponse` 형태로 바뀌었다. 삭제된 컬렉션이 목록에서 빠진다는 결론 자체는 그대로 유효.)

### 자동 테스트

```bash
$ ./gradlew test --tests "*CollectionCommandServiceTest*" --tests "*CollectionQueryServiceTest*"
BUILD SUCCESSFUL
```
`CollectionCommandServiceTest`(총 15개)에 `deleteCollection`/`removeDocument` 관련 케이스가 `#16`의 `createCollection`/`addDocument` 케이스와 함께 묶여 있다(클래스 자체는 `#16`에서 이미 생성, 이 이슈에서 케이스만 추가). `CollectionQueryServiceTest`(총 4개, `#16` 문서 참고 — `canReadCollection` 도입으로 케이스 추가됨). 실제로는 4개 전부 `getCollection` 케이스이고 `getMyCollections`에 대한 단위 테스트는 없다 — 코드리뷰로 발견된 기존 테스트 커버리지 공백이며, 별도로 추가가 필요하다.

---

## 에러 케이스 정리

| 상황 | HTTP | 코드 |
|---|---:|---|
| 컬렉션 없음 | 404 | `COLLECTION-001` |
| 컬렉션 소유자가 아님(삭제/문서 제거 모두) | 403 | `ROLE-002`(`PERMISSION_DENIED`) |
| 컬렉션에 해당 문서가 연결되어 있지 않음 | 404 | `COLLECTION-003` |

소유자가 아닌 경우 `canAdminCollection()`(권한 위임자도 통과)이 아니라 `collection.getOwner().getId().equals(userId)`로 직접 비교하므로, ADMIN 권한을 부여받은 사용자라도 이 두 API는 403이 난다 — `#18` 문서의 권한 부여/회수 API와 다른 지점이니 프론트에서 혼동하지 않도록 주의.

---

## 설계 결정 요약

**삭제 계열 API를 소유자 전용으로 못박은 이유**: `#18`의 grant/revoke는 컬렉션 관리 권한이 있는 사용자가 다른 사람에게 권한을 나눠줄 수 있어야 협업이 되므로 그 권한을 인정했다. 반면 컬렉션 자체를 지우거나 컬렉션에서 문서를 빼는 행위는 "그 컬렉션의 존재/구성 자체"를 바꾸는 것이라, 권한을 넘겨받은 사람이 실수로(혹은 악의적으로) 원 소유자 동의 없이 컬렉션을 통째로 날릴 수 있는 상황을 막기 위해 소유자로 제한했다.

**두 벌크 무효화 쿼리를 범위(scope)로 구분**: `bulkInvalidateBySource`(소스 전체)와 `bulkInvalidateBySourceIdsAndDocument`(소스+문서 교집합)를 애초에 `#18`에서 두 개로 나눠 만들어 둔 이유가 여기서 드러난다 — 컬렉션 삭제는 "이 권한 소스로 파생된 모든 캐시"를 지워야 하고, 문서 제거는 "이 권한 소스 중 이 문서에 한정된 캐시"만 지워야 하는, 서로 다른 스코프의 무효화가 둘 다 필요했기 때문이다.

**ROLE/DEPARTMENT 캐시 무효화를 하지 않는 이유(반복 확인)**: `#18`의 비대칭 캐싱 설계상 ROLE/DEPARTMENT 대상 권한은 애초에 캐시 테이블에 들어간 적이 없다(`grantPermission()`이 `USER` 타입에만 캐시를 만든다). 따라서 이 두 삭제 API도 `.filter(p -> p.getTargetType() == USER)`로 USER만 골라 무효화 대상으로 삼고, ROLE/DEPARTMENT 권한은 캐시 무효화 없이 레코드만 삭제된다 — `#21`의 4단계(ROLE)/5단계(DEPT) live 조회가 다음 호출부터 즉시 최신 상태를 반영해주기 때문에 문제없다.

---

## 남은 이슈 / TODO

- ~~`getMyCollections()`가 페이지네이션 없이 전체 목록을 반환한다 — 컬렉션 수가 많아지는 시나리오는 아직 없어 이슈로 등록하지 않음.~~ → **2026-08-18 해결됨**: `getCollections(userId, keyword, page, size)`로 교체, `PageResponse` 반환. 아래 "이후 업데이트" 참고.
- ~~`deleteCollection()`/`removeDocument()` 둘 다 `collectionRepository.findById()`로만 컬렉션을 조회한다 — `#16`에서 이미 지적된 것과 같은 이유로, 이미 `status=DELETED`인 컬렉션에 대해서도 (멱등하게) 재호출이 가능하다.~~ → 해결됨: 두 메서드 모두 `findById(...).filter(c -> c.getStatus() != CollectionStatus.DELETED)`로 변경(`#16`과 동일 패턴, `PermissionQueryService`의 `getActiveCollection()` 헬퍼와 동일한 관용구).

## 이후 업데이트 (2026-08-18, 이슈 #229 및 관련 작업)

수동 QA(시나리오 4) 중 이 문서가 만든 `getMyCollections()`/`deleteCollection()` 둘 다 실질적으로 다시 손보게 됐다. 상세 설계는 신규 문서 `docs/design/kangcheolung-#229-collection-tree.md` 참고.

**`deleteCollection()` — cascade 삭제로 확장 (이슈 #229 본편)**
- `CollectionRepository.findDescendantIdsInclusive(collectionId)`(자기 자신+모든 후손, `WITH RECURSIVE`)로 대상 전체 ID를 구한 뒤, 권한 삭제·캐시 무효화·soft delete를 전부 그 목록 전체에 대해 수행하도록 확장.
- **문서 매핑(`collection_documents`)도 이번에 같이 지우도록 범위가 넓어졌다** — 원래는 컬렉션만 지우고 매핑은 안 건드렸는데(위 "DB 변화 예시" 참고), cascade 대상 전체의 `CollectionDocument`도 함께 삭제한다.
- owner 체크는 **삭제 대상 root 1회만** 하고 하위 각각은 재확인하지 않는다 — 구글드라이브 공유폴더 삭제와 같은 멘탈모델("최상위에 대한 권한으로 하위 전체가 지워짐"). 트레이드오프: 하위 컬렉션의 owner가 root owner와 다를 수 있는데(부모에 쓰기권한만 있으면 자식을 만들 수 있으므로), 그 경우도 root owner가 삭제할 수 있다.

**`getMyCollections()` → `getCollections()` — 권한 반영 + 페이지네이션 + 검색 (관련 작업)**
- 이름 그대로 "owner 것만"이라 문서 목록(`GET /api/documents`, owner+PUBLIC+권한부여 전부 포함)과 비대칭이었던 게 QA 중 재발견됨.
- `CollectionRepository.findReadableCollectionIds(userId, keyword)` 신규 — owner+PUBLIC+USER직접권한+ROLE+DEPARTMENT(+부모 컬렉션 상속)를 전부 포함하는 native 쿼리. `DocumentRepository.findReadableDocumentIds`와 동일한 UNION 패턴.
- `GET /collections?keyword=&page=&size=`로 페이지네이션과 이름/설명 검색까지 같이 추가.
- 안 쓰이게 된 `findAllByOwnerIdAndStatus()`는 삭제.

## 다음 단계

권한 블록(`#16`, `#18`, `#21`, `#24`, `#29`) 전체 완료. RAG 블록(F-SEARCH, F-RAG)이 이 블록의 `PermissionQueryService.canReadDocument()`를 검색 단계 권한 필터링에 그대로 재사용한다(이미 완료·문서화됨: `docs/design/kangcheolung-#65-*.md` 이하 RAG 문서 시리즈). 이후 `#229`(컬렉션 트리)로 이어진다.
