# #229 컬렉션 트리(하위 컬렉션) 지원

closes #229

---

## 배경

`DocumentCollection.parentCollection`(self-FK)은 `#16`(컬렉션 CRUD) 때부터 스키마·엔티티·DTO에 존재했지만, `#16` 설계 문서에 이미 명시된 대로 "생성 시 상위 컬렉션 존재 확인" 정도만 쓰고 트리 순회 API는 만들지 않은 상태였다(Simplicity First — 당장 필요 없는 API를 미리 만들지 않음).

시나리오 5(권한 부여·회수) 수동 QA를 시작하려면 먼저 컬렉션/문서가 있어야 해서 시나리오 4(컬렉션 CRUD)부터 다시 훑다가, `parentCollectionId`가 실질적으로 死코드라는 걸 재발견했다:

- 자식 컬렉션 조회 API가 없어서, 생성 시 부모를 지정해도 나중에 그 관계를 확인할 방법이 없었다.
- `createCollection()`이 부모 컬렉션의 **존재 여부만** 확인하고 **권한은 확인하지 않아서**, 남의 컬렉션 밑에도 마음대로 자식을 매달 수 있는 버그가 있었다.
- 부모 컬렉션에 준 권한이 자식 컬렉션·문서에 상속되지 않았다.
- 컬렉션 삭제가 자기 자신만 지우고 하위 컬렉션은 고아로 남겼다.
- 프론트(`CollectionsPage.tsx`)도 생성 시 `parentCollectionId`를 항상 `null`로 고정 전송하고 있어 UI로는 절대 하위 컬렉션을 만들 수 없었다.

`#226`(역할 회수 API)과 같은 패턴 — "QA 중 발견 → 근본 원인 파악 → 실제로 고침" — 으로 이 이슈를 만들어 실제 트리 기능을 완성했다. 목표는 파인더/구글드라이브 폴더와 동일한 동작:

- **탐색**: 파인더 방식 — 클릭(하위 조회 API 호출)해야 그 안이 보임 (전체 트리를 한 번에 안 내려줌)
- **권한**: 구글드라이브 공유폴더 방식 — 부모 컬렉션에 준 DEPARTMENT/ROLE 권한이 자식 컬렉션·문서까지 자동 상속. **문서 단건 조회뿐 아니라 문서 목록·검색 결과에도 동일하게 반영**되도록 스코프를 넓혔다(아래 "왜 목록/검색까지 포함했나" 참고).
- **삭제**: 실제 폴더 방식 — 부모를 지우면 하위 전체가 cascade soft delete

---

## 왜 목록/검색까지 포함했나 (스코프 결정)

권한 상속을 문서 단건 조회(`PermissionQueryService`)에만 넣으면, "권한은 있는데 폴더를 열거나 검색하면 안 보이는" 모순이 생긴다 — 문서 ID를 직접 알아야만 접근 가능하고, 사람들이 실제로 문서를 찾는 방식(폴더 탐색, 검색)으로는 못 찾는 상태가 된다. 그래서 `DocumentRepository`의 목록/검색 pre-filter native 쿼리 2개까지 이번 스코프에 포함시켰다(사용자가 명시적으로 선택).

---

## 전체 흐름

```text
POST /collections (parentCollectionId 지정)
    │
    ▼
CollectionCommandService.createCollection()
    ├─ 부모 컬렉션 존재 확인 (기존과 동일)
    └─ (신규) 부모 컬렉션 쓰기권한 확인 → 없으면 403
    │
    ▼
GET /collections/{id}/children (신규)
    │
    ▼
CollectionQueryService.getChildren()
    ├─ 부모 읽기권한 확인
    ├─ 직계 자식만 조회 (손자는 안 섞임)
    └─ 자식마다 개별 읽기권한 재확인 (자식 owner/visibility가 부모와 다를 수 있음)

DELETE /collections/{id} (cascade로 확장)
    │
    ▼
CollectionCommandService.deleteCollection()
    ├─ root owner 1회만 확인 (하위는 재확인 안 함)
    ├─ 자기 자신 + 모든 후손 ID 재귀 조회
    ├─ 대상 전체의 권한 삭제 + USER 캐시 무효화
    ├─ 대상 전체의 문서 매핑(collection_documents) 삭제 (신규 — 예전엔 안 지웠음)
    └─ 대상 전체 soft delete

문서/컬렉션 권한 판단 (PermissionQueryService, DocumentRepository)
    │
    ▼
기존 단계 전부 통과 못하면
    └─ (신규) 마지막 단계: 부모 컬렉션 체인 상속 확인
```

---

## 신규/변경 파일

### 1. `domain/collection/repository/CollectionRepository.java` — 재귀 쿼리 4개 + 자식 조회

```java
// 직계 자식 컬렉션 목록 조회 (GET /collections/{id}/children)
List<DocumentCollection> findAllByParentCollectionIdAndStatus(Long parentCollectionId, CollectionStatus status);

/**
 * 자기 자신 + 모든 조상 컬렉션 ID (권한 상속 판단용).
 * 삭제된 조상도 결과에 포함한다 — 삭제된 컬렉션은 권한이 비어있어 무해하고,
 * status 필터를 넣으면 중간 조상이 삭제됐을 때 그 위 조상으로 체인이 끊기는 문제가 생긴다.
 */
@Query(value = """
        WITH RECURSIVE ancestors AS (
            SELECT id, parent_collection_id FROM collections WHERE id = :collectionId
            UNION ALL
            SELECT c.id, c.parent_collection_id
            FROM collections c
            JOIN ancestors a ON c.id = a.parent_collection_id
        )
        SELECT id FROM ancestors
        """, nativeQuery = true)
List<Long> findAncestorIdsInclusive(@Param("collectionId") Long collectionId);

// 자기 자신 + 모든 후손 컬렉션 ID (cascade 삭제 대상 판단용) — 위와 대칭 구조, parent_collection_id로 아래로 내려감
List<Long> findDescendantIdsInclusive(@Param("collectionId") Long collectionId);

/**
 * 문서가 속한 모든 컬렉션(N:M) + 그 컬렉션들 각각의 조상 전체 ID (문서 권한 상속 판단용).
 */
@Query(value = """
        WITH RECURSIVE ancestors AS (
            SELECT c.id, c.parent_collection_id
            FROM collections c
            WHERE c.id IN (
                SELECT DISTINCT cd.collection_id FROM collection_documents cd WHERE cd.document_id = :documentId
            )
            UNION ALL
            SELECT c.id, c.parent_collection_id
            FROM collections c
            JOIN ancestors a ON c.id = a.parent_collection_id
        )
        SELECT DISTINCT id FROM ancestors
        """, nativeQuery = true)
List<Long> findEffectiveCollectionIdsForDocument(@Param("documentId") Long documentId);
```

네이티브 재귀 쿼리(`WITH RECURSIVE`)가 코드베이스에 이번이 처음이라, 기존 컨벤션(`DocumentRepository.findReadableDocumentIds*` — `@Query(nativeQuery=true)`, snake_case, `List<Long>` 반환 후 2차 필터링에 사용)을 그대로 따랐다.

**순환 참조 방지 로직은 만들지 않았다.** 컬렉션 이동/수정 API가 없어서 생성 시점에만 부모를 지정할 수 있고, 아직 존재하지 않는 컬렉션은 자기 자신의 조상이 될 수 없으므로 현재 API 구조상 순환 참조가 원천적으로 불가능하다(검토 완료). 나중에 컬렉션 이동 API가 생기면 그때 재검토해야 한다.

### 2. `domain/collection/service/command/CollectionCommandService.java` — 부모 권한 체크 + cascade 삭제

```java
// 폴더 생성
public CollectionResponse createCollection(Long userId, CreateCollectionRequest request) {
    User owner = userRepository.getReferenceById(userId);

    DocumentCollection parentCollection = null;
    if (request.parentCollectionId() != null) {
        parentCollection = collectionRepository.findById(request.parentCollectionId())
                .filter(c -> c.getStatus() != CollectionStatus.DELETED)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        if (!permissionQueryService.canWriteCollection(userId, parentCollection)) {   // 신규
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }
    }
    // ... 이하 기존과 동일
}

// 컬렉션 soft delete — 소유자만 가능. 하위 컬렉션 전체와 그 안의 문서 매핑까지 cascade로 함께 삭제한다.
// owner 체크는 삭제 대상 최상위(root)에서만 하고 하위 각각은 재확인하지 않는다
// (구글드라이브 공유폴더 삭제와 동일한 멘탈모델 — root에 대한 권한으로 하위 전체가 지워짐).
public void deleteCollection(Long collectionId, Long userId) {
    DocumentCollection root = collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

    if (!root.getOwner().getId().equals(userId)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }

    List<Long> targetIds = collectionRepository.findDescendantIdsInclusive(collectionId); // 자기 자신 포함

    List<CollectionPermission> permissions = collectionPermissionRepository.findAllByCollectionIdIn(targetIds);
    permissions.stream()
            .filter(p -> p.getTargetType() == PermissionTargetType.USER)
            .forEach(p -> cacheService.bulkRevokeBySource(AccessSourceType.DIRECT_COLLECTION_PERMISSION, p.getId()));
    collectionPermissionRepository.deleteAll(permissions);

    List<CollectionDocument> mappings = collectionDocumentRepository.findAllByCollectionIdIn(targetIds);
    collectionDocumentRepository.deleteAll(mappings);   // 신규 — #29 원래 버전은 문서 매핑을 안 지웠음

    LocalDateTime now = LocalDateTime.now();
    collectionRepository.findAllById(targetIds).forEach(c -> c.markDeleted(now));
}
```

`createCollection()`에서 부모의 **엔티티 오버로드**(`canWriteCollection(userId, DocumentCollection)`)를 쓰는 부수 효과로, 내부 `validateActiveCollection()`이 "삭제된 부모 아래 생성 금지"도 자동으로 막아준다(별도 코드 없이 잠재 버그 하나 더 해결).

`deleteCollection()`의 owner 체크가 root 1회뿐이라는 트레이드오프: 부모에 쓰기권한만 있는 사람도 자식 컬렉션을 만들 수 있으므로(위 `createCollection()` 변경), 이론상 자식의 owner가 root owner와 다를 수 있다. 그래도 root owner가 cascade 삭제를 실행할 수 있다 — 구글드라이브에서 상위 폴더 소유자가 하위 폴더(다른 사람이 만든 것 포함)까지 지울 수 있는 것과 같은 모델.

### 3. `domain/collection/service/query/CollectionQueryService.java` / `controller` — `GET /collections/{id}/children`

```java
// 직계 자식 컬렉션 목록 조회 — 부모 읽기 권한 확인 후, 자식 각각의 읽기 권한도 확인
// (자식 owner/visibility가 부모와 다를 수 있으므로 부모 권한만으로 자식을 노출하면 안 됨)
public List<CollectionResponse> getChildren(Long userId, Long collectionId) {
    DocumentCollection parent = collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
    if (!permissionQueryService.canReadCollection(userId, parent)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }

    return collectionRepository.findAllByParentCollectionIdAndStatus(collectionId, CollectionStatus.ACTIVE)
            .stream()
            .filter(child -> permissionQueryService.canReadCollection(userId, child))
            .map(collectionConverter::toResponse)
            .toList();
}
```
자식 개수가 적을 것으로 예상해 N+1(자식마다 `canReadCollection` 호출)을 허용했다 — 문서 목록처럼 대량+SQL prefilter로 짜는 건 지금 스코프에서 오버엔지니어링으로 판단. 페이지네이션도 없이 `List` 반환(`getMyCollections()`와 동일 컨벤션, 자식 수가 적을 거라는 전제).

### 4. `domain/permission/service/query/PermissionQueryService.java` — 6개 판정 그룹 전부에 상속 단계 추가

문서 판단 4종(`canReadDocument`/`canWriteDocument`/`canAdminDocument`/`checkDocumentPermission`)과 컬렉션 판단 3종(`canReadCollection`/`canWriteCollection`/`canAdminCollection`, 엔티티 오버로드) 전부에 마지막 단계로 상속 체크가 추가됐다. 예시(`canReadDocument`):

```java
// 6단계: 부모 컬렉션 체인 상속 (ROLE/DEPARTMENT)
List<Long> effectiveCollectionIds = collectionRepository.findEffectiveCollectionIdsForDocument(documentId);
if (!effectiveCollectionIds.isEmpty()
        && (collectionPermissionRepository.existsRoleReadPermissionForCollections(userId, effectiveCollectionIds)
                || collectionPermissionRepository.existsDeptReadPermissionForCollections(userId, effectiveCollectionIds))) {
    return true;
}
```
컬렉션 판단(예: `canReadCollection` 엔티티 오버로드)은 `findAncestorIdsInclusive`를 쓴다는 것만 다르고 구조는 동일:
```java
List<Long> ancestorIds = collectionRepository.findAncestorIdsInclusive(collectionId);
if (collectionPermissionRepository.existsRoleReadPermissionForCollections(userId, ancestorIds)) return true;
return collectionPermissionRepository.existsDeptReadPermissionForCollections(userId, ancestorIds);
```

**전부 기존 로직 뒤에 append만 했다** (기존 줄은 한 글자도 안 고침) — 대안으로 기존 12개 `existsXxxForCollection`/`existsXxxForDocument` 메서드 시그니처를 `List<Long>` 받게 바꾸는 방법도 검토했지만, 그러면 `PermissionQueryServiceTest`(817줄, 기존 47개 케이스가 그 메서드들을 개별 스텁)가 대량으로 깨진다. 대신 `List<Long>` 버전 6개(`existsRole/DeptRead/Write/AdminPermissionForCollections`, 복수형)를 **신규 추가**해서 기존 스텁 안 된 메서드는 Mockito가 기본값(`false`/`[]`)을 반환 → 기존 테스트 47개 전부 무변경 통과.

`checkDocumentPermission()`의 6단계는 새 `PermissionSourceType` 값을 만들지 않고 기존 `ROLE`/`DEPARTMENT`를 재사용한다 — API 응답 스키마 불변(프론트 영향 없음), 트레이드오프는 "직접 부여 vs 조상 상속"을 이 응답만으로 구분 못 한다는 것(감사 시 조상까지 직접 추적 필요).

### 5. `domain/document/repository/DocumentRepository.java` — 목록/검색 pre-filter에도 상속 반영

`findReadableDocumentIds`(전체 목록/검색), `findReadableDocumentIdsInCollection`(컬렉션 내 목록) 두 native UNION 쿼리 최상단에 컬렉션 조상 closure CTE를 추가하고, 컬렉션 ROLE/DEPARTMENT 브랜치의 JOIN 조건을 이 closure를 거치도록 바꿨다:

```sql
WITH RECURSIVE collection_ancestors AS (
    SELECT id AS collection_id, id AS ancestor_id FROM collections
    UNION ALL
    SELECT ca.collection_id, c.parent_collection_id AS ancestor_id
    FROM collection_ancestors ca
    JOIN collections c ON c.id = ca.ancestor_id
    WHERE c.parent_collection_id IS NOT NULL
)
SELECT d.id FROM documents d
  ...
UNION
SELECT d.id FROM documents d
  JOIN collection_documents cd ON cd.document_id = d.id
  JOIN collection_ancestors ca ON ca.collection_id = cd.collection_id      -- 변경 지점
  JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id      -- 변경 지점 (기존: cp.collection_id = cd.collection_id)
  JOIN user_roles ur ON ur.role_id = cp.role_id
WHERE cp.target_type = 'ROLE' AND ur.user_id = :userId AND cp.can_read = true
  ...
```
`ancestor_id`가 자기 자신(distance 0)을 포함하므로 기존 "직접 권한" 케이스도 이 브랜치 하나로 그대로 커버된다 — 별도 브랜치 추가가 아니라 순수 대체. `findReadableDocumentIdsInCollection`의 바깥쪽 `WHERE sub.id IN (SELECT document_id FROM collection_documents WHERE collection_id = :collectionId)`는 그대로 유지 — "이 컬렉션에 직접 속한 문서만 나열"이라는 폴더 탐색 시맨틱(하위 폴더 문서가 상위 목록에 안 섞임)은 안 바뀌어야 한다.

호출부(`CollectionQueryService.getCollectionDocuments`, `DocumentQueryService`, `AccessibleDocumentQueryService`/`SearchFacade`)는 수정 불필요 — 쿼리 결과가 정확해지면 자동 반영된다.

### 6. 프론트 — `CollectionsPage.tsx`

- 생성 모달에 "상위 폴더" 드롭다운 추가 (선택 안 하면 최상위)
- 컬렉션 상세 페이지에 "하위 컬렉션" 섹션 추가 — `GET /collections/{id}/children` 조회, 클릭하면 그 컬렉션 상세로 이동(파인더처럼 한 단계씩 열람, 트리를 한 번에 펼치지 않음)
- 삭제 확인 문구를 하위 컬렉션이 있을 때만 cascade 경고로 분기:
```ts
const warning = children.length
  ? "이 컬렉션을 삭제할까요? 하위 컬렉션과 그 안의 문서도 전부 함께 삭제됩니다. 복구 API는 제공되지 않습니다."
  : "이 컬렉션을 삭제할까요? 복구 API는 제공되지 않습니다.";
```

---

## 관련 작업 (같은 세션에서 QA 중 발견해서 이어서 구현, #229 본편은 아님)

컬렉션 트리 자체는 아니지만 같은 QA 흐름에서 발견해서 같이 처리한 3건. GitHub 이슈 #229 body에도 "🔗 QA 중 함께 발견·구현한 관련 작업" 섹션으로 반영해뒀다.

### A. ROLE=USER 권한부여 시 전체공개되는 위험 차단

`USER` role은 가입 시 전원에게 자동 부여되는 기본 role이다. `targetType=ROLE`로 권한을 부여할 때 이 role을 대상으로 지정하면, ROLE 판단 쿼리(`JOIN user_roles ur ON ur.role_id = cp.role_id`)가 "이 role_id를 가진 모든 사용자"를 매칭하므로 사실상 전 직원한테 뚫린다 — `visibility=PUBLIC`보다도 넓은 범위(PUBLIC은 READ만 열지만 이 경로는 WRITE/ADMIN도 전체 공개 가능).

```java
// ErrorCode.java
ROLE_NOT_GRANTABLE(HttpStatus.BAD_REQUEST, "PERMISSION-004",
        "USER role은 모든 사용자가 보유하고 있어 권한 부여 대상으로 지정할 수 없습니다. 전체 공개가 목적이면 visibility를 PUBLIC으로 설정하세요."),
```
```java
// DocumentPermissionCommandService / CollectionPermissionCommandService — grantPermission()
} else if (request.targetType() == PermissionTargetType.ROLE) {
    targetRole = roleRepository.findById(request.roleId())
            .orElseThrow(() -> new DocGridException(ErrorCode.ROLE_NOT_FOUND));
    if ("USER".equals(targetRole.getCode())) {
        throw new DocGridException(ErrorCode.ROLE_NOT_GRANTABLE);
    }
}
```
ADMIN role은 문서/컬렉션 접근에 아무 특별 취급이 없다(`/admin/**` API만 열어줌 — `SecurityConfig`)는 것도 이번에 재확인했다. "ADMIN이면 다 보이겠지"는 착각이라, ROLE=USER 위험을 막을 별도 안전망이 없다는 근거로 이 가드가 더 중요해졌다.

### B. `GET /roles` 신규 + 권한 부여 폼 이름 드롭다운

권한 부여 폼에서 ROLE/DEPARTMENT 대상 ID를 숫자로 외워서 입력해야 하는 게 비현실적이었다. `DepartmentController`(`GET /departments`)와 동일한 패턴으로 `RoleController`/`RoleQueryService`/`RoleResponse` 신규 추가(`GET /roles`, 인증 필요 — `/departments`는 회원가입 화면용이라 `permitAll`이지만 역할 선택은 로그인 후에만 쓰이므로 기본 인증 유지).

프론트 `PermissionsPage.tsx`의 "권한 부여" 폼에서 `targetType`을 controlled state로 바꾸고, "대상 ID" 필드를 분기:
- USER: 숫자 input 유지 (전체 사용자 목록 조회는 관리자 전용이라 이름 드롭다운으로 못 바꿈)
- ROLE: `roles.filter(role => role.code !== "USER")` 이름 드롭다운 — 위 A번 가드와 자연스럽게 맞물려 USER는 UI에서부터 선택 불가
- DEPARTMENT: `departments` 이름 드롭다운

### C. 컬렉션 목록(`GET /collections`) 권한 반영 + 페이지네이션 + 검색

`CollectionQueryService.getMyCollections()`(owner만, `List` 반환)가 문서 목록(`GET /api/documents`, owner+PUBLIC+권한부여 전부 포함)과 비대칭이라는 게 QA 중 재발견됐다. `getCollections(userId, keyword, page, size)`로 완전히 교체:

```java
// CollectionRepository — findReadableDocumentIds와 동일한 UNION 패턴, keyword는 nullable
@Query(value = """
        WITH RECURSIVE collection_ancestors AS ( ... )   -- 위 5번과 동일한 closure
        SELECT c.id FROM collections c
        WHERE c.owner_user_id = :userId AND c.status = 'ACTIVE'
          AND (:keyword IS NULL OR c.name ILIKE CONCAT('%', :keyword, '%') OR c.description ILIKE CONCAT('%', :keyword, '%'))
        UNION
        SELECT c.id FROM collections c
        WHERE c.visibility = 'PUBLIC' AND c.status = 'ACTIVE' AND (:keyword IS NULL OR ...)
        UNION  -- USER 직접 권한
        UNION  -- ROLE (조상 상속 포함)
        UNION  -- DEPARTMENT (조상 상속 포함)
        """, nativeQuery = true)
List<Long> findReadableCollectionIds(@Param("userId") Long userId, @Param("keyword") String keyword);

@Query("SELECT c FROM DocumentCollection c JOIN FETCH c.owner WHERE c.id IN :ids")
Page<DocumentCollection> findAllByIdIn(@Param("ids") List<Long> ids, Pageable pageable);
```
`GET /collections?keyword=&page=&size=`. 검색은 문서처럼 임베딩 기반이 아니라 이름/설명 단순 `ILIKE` 필터 — 컬렉션은 구조화된 메타데이터뿐이라 무거운 semantic search가 필요 없다고 판단. 안 쓰이게 된 `findAllByOwnerIdAndStatus()`는 삭제.

프론트 `CollectionsPage.tsx` 메인 목록에 페이지네이션 컨트롤 추가(`CollectionDetailPage`의 기존 패턴 재사용), 검색창은 `AdminPages.tsx`의 기존 `toolbar`/`toolbar-search`+`toQuery` 패턴 재사용. `SearchPage.tsx`/`PermissionsPage.tsx`의 컬렉션 드롭다운도 응답이 `PageResponse`로 바뀐 데 맞춰 `.content` 사용하도록 수정(각각 `size=100`으로 넉넉히 조회).

---

## 로컬 검증

Swagger/프론트 수동 QA는 진행했으나, 항목별 pass/fail 세부 기록은 별도 정리 전. 아래는 자동 테스트 기준.

### 자동 테스트

```bash
$ ./backend/gradlew -p backend test --tests "com.opensource.docgrid.domain.document.*" \
    --tests "com.opensource.docgrid.domain.collection.*" --tests "com.opensource.docgrid.domain.permission.*" \
    --tests "com.opensource.docgrid.domain.search.*" --tests "com.opensource.docgrid.domain.user.*"
```
document/collection/permission/search/user 도메인 전체 통과(354개 중 354개, 2026-08-18 재검증분 기준). 실패 9개(`DocumentUploadIntegrationTest`/`DocumentVersionUploadIntegrationTest`/`DocumentStatusRepositoryTest`)는 로컬 임베딩 모델 미설정이라는 **기존 환경 문제** — 이번 변경 전 원본 코드로도 동일하게 재현되는 것을 `git stash`로 직접 확인해 무관함을 검증했다.

신규 `@DataJpaTest`(`CollectionTreeRepositoryTest`)로 실제 로컬 Postgres에 재귀 쿼리·keyword 필터를 직접 실행해 검증:
- `findAncestorIdsInclusive`/`findDescendantIdsInclusive`/`findEffectiveCollectionIdsForDocument`/`findAllByParentCollectionIdAndStatus` — 3단 트리(root→child→grandchild) 구성해서 검증
- `findReadableCollectionIds` — owner/PUBLIC 노출 + DEPARTMENT 권한이 부모에서 자식까지 상속되는지 + keyword 필터

기존 `DocumentReadableIdsRepositoryTest`도 확장 — 부모 컬렉션에만 권한 있고 자식 컬렉션 문서를 조회하는 케이스(상속 정상 동작) + 기존 "직접 권한만" 케이스가 회귀 없이 통과하는지 같이 검증.

`PermissionQueryServiceTest`는 47→54개(상속 케이스 7개 신규 + 관련 없음 3개는 없음, 정확히는 6개 판정그룹 각 1개 상속 테스트 + `checkDocumentPermission` 상속 테스트 1개 = 7개)로 늘었고, **기존 47개는 단 한 줄도 안 고쳤는데 그대로 통과**했다(추가 방식 설계가 의도대로 작동함을 증명).

---

## 에러 케이스 정리

| 상황 | HTTP | 코드 |
|---|---:|---|
| 상위 컬렉션 없음(생성 시) | 404 | `COLLECTION-001` |
| 상위 컬렉션에 쓰기권한 없음(생성 시, 신규) | 403 | `ROLE-002`(`PERMISSION_DENIED`) |
| 자식 컬렉션 조회 시 부모 없음/삭제됨 | 404 | `COLLECTION-001` |
| 자식 컬렉션 조회 시 부모 읽기권한 없음 | 403 | `ROLE-002`(`PERMISSION_DENIED`) |
| ROLE 대상이 USER role임(관련 작업 A) | 400 | `PERMISSION-004`(`ROLE_NOT_GRANTABLE`) |

---

## 설계 결정 요약

**기존 권한 판정 메서드는 손대지 않고 전부 append 방식으로 확장**: `PermissionQueryService`의 12개 기존 `existsXxxFor...` 메서드를 시그니처 변경(치환)하면 817줄짜리 기존 테스트가 대량으로 깨진다. 대신 `List<Long>` 버전을 신규 추가하고 기존 5단계 로직 끝에 6단계로 이어붙이는 방식을 택해, 인증/인가 critical path의 기존 검증된 로직을 안 건드리면서 상속을 추가했다.

**순환 참조 방지 로직 생략**: 컬렉션 이동 API가 없어 생성 시점에만 부모 지정이 가능하므로, 현재 API 구조상 순환 참조가 원천적으로 불가능하다(검토 완료). 이동 API를 나중에 추가하게 되면 그때 반드시 재검토해야 한다.

**목록/검색 쿼리(`DocumentRepository`)까지 상속 반영 범위에 포함**: 단건 조회만 상속되면 "권한은 있는데 못 찾는" 상태가 되므로, 원래 계획보다 범위를 넓혀 native 쿼리 2개를 같이 고쳤다(사용자가 명시적으로 이 범위까지 선택).

**컬렉션 삭제 cascade는 root owner 1회 체크**: 구글드라이브 공유폴더 삭제와 동일한 멘탈모델. 하위 컬렉션 owner가 root와 다를 수 있다는 트레이드오프를 감수했다(위 신규 파일 2번 참고).

**`checkDocumentPermission()`의 상속 출처는 새 enum 값 없이 기존 `ROLE`/`DEPARTMENT` 재사용**: API 응답 스키마 불변으로 프론트 영향 없음. "직접 부여 vs 상속"을 구분 못 한다는 정보 손실은 감수.

**ROLE=USER 차단은 프론트가 아니라 백엔드에서**: `PermissionsPage.tsx`의 대상 타입 드롭다운을 아무리 잘 막아도 Swagger/curl로 API를 직접 호출하면 우회된다 — 실제 안전장치는 서버 검증(`ROLE_NOT_GRANTABLE`)이고, 프론트 드롭다운 필터링은 UX 보조일 뿐이다.

---

## 남은 이슈 / TODO (백로그, 이번 스코프 아님)

- **컬렉션 상속용 재귀 CTE(`collection_ancestors`)가 매 호출마다 컬렉션 테이블 전체를 스캔한다** — `findAncestorIdsInclusive(collectionId)`처럼 `WHERE id = :collectionId`로 범위를 좁힌 쿼리는 문제없지만, `findReadableDocumentIds`/`findReadableDocumentIdsInCollection`/`findReadableCollections`(구 `findReadableCollectionIds`) 안의 closure는 범위 제한이 없다. 검색·문서목록·컬렉션목록처럼 호출 빈도가 높은 화면에 다 걸려있어서, 컬렉션 수가 많아지면 병목 후보 1순위다. 지금 규모(수십~수백 개 추정)에선 무해. (2026-08-19: 아래 두 항목은 `#240`으로 해결됐지만, 이 CTE 전체 스캔 자체는 여전히 남아있는 별개 이슈 — `docs/design/kangcheolung-#240-collection-list-pagination.md`의 "남은 이슈" 참고)
- ~~`GET /collections`가 전체 ID를 먼저 찾고 그중 일부를 재조회하는 2단계 구조~~ → `#240`에서 `findReadableCollections`(COUNT(*) OVER()로 콘텐츠+총개수 한 쿼리)로 해결
- ~~`GET /collections/{id}/children`이 자식마다 `canReadCollection`을 반복 호출(N+1)~~ → `#240`에서 `findReadableChildren`(권한 조건을 SQL로 이관)로 해결
- `DOCUMENT_MANAGER` role 관련 작업은 이번에도 스코프 제외 (별도 논의 필요).
- 프론트 트리 탐색 UI는 "클릭해서 한 단계씩 열람"만 구현 — 여러 단계를 한 번에 펼쳐 보여주는 UI는 안 만듦(파인더 방식 그대로).

## 다음 단계

컬렉션/권한 블록(`#16`, `#18`, `#21`, `#24`, `#29`)에 이어지는 후속 이슈로, 이 다섯 문서에도 "이후 업데이트" 절로 교차 반영해뒀다. 커밋은 아직 안 한 상태 — 이 이슈에 관련 작업 A/B/C를 같이 넣을지 별도로 분리할지는 미정.
