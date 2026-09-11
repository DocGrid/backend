# #322 자식 컬렉션 목록 조회 페이지네이션

closes #322

---

## 배경

`GET /collections/{id}`(단건)/`GET /collections`(전체 목록)는 #240에서 이미
`page`/`size` + `COUNT(*) OVER()`로 페이지네이션됐는데, 같은 구조를 쓰는
`GET /collections/{id}/children`(직계 자식 조회)만 이 처리가 빠져 있었다.
자식 수가 적을 땐 체감이 안 되지만, 한 폴더 밑에 자식이 수백~수천 개 쌓이면
`findReadableChildren`이 매번 전체를 한 번에 반환하게 된다. 응답이 엔티티
(`DocumentCollection`) 그대로라 `CollectionConverter.toResponse(DocumentCollection, String)`가
자식마다 `owner.getName()`을 지연 로딩으로 채워야 했던 것도 함께 정리 대상이었다.

---

## 문제상황

```java
// 수정 전 — CollectionQueryService.getChildren
public List<CollectionResponse> getChildren(Long userId, Long collectionId) {
    DocumentCollection parent = collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
    if (!permissionQueryService.canReadCollection(userId, parent)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }
    return collectionRepository.findReadableChildren(collectionId, userId)
            .stream()
            .map(child -> collectionConverter.toResponse(child, ownerNameOf(child)))
            .toList();
}
```

- `findReadableChildren`이 `List<DocumentCollection>`을 개수 제한 없이 전부 반환.
- 자식마다 owner 이름을 얻으려면 `child.getOwner().getName()`을 거쳐야 하는데, 이건
  지연 로딩된 `User` 프록시 초기화라 자식 수만큼 추가 쿼리(N+1)가 나간다.
- `GET /collections`는 이미 `owner_name`을 쿼리에서 조인해서 이 문제를 해결했는데
  `children`만 빠져 있었다.

---

## 설계

`findReadableCollections`/`countReadableCollections`(#240)와 동일한 패턴을 그대로
적용한다.

- `parent_ancestors` CTE(앵커 있음 — `WHERE id = :parentId`로 시작점이 명확히 고정된
  경우라 #312에서 closure table로 바꾼 앵커-less CTE와는 다른 케이스)는 그대로 유지한다.
- `SELECT c.*` → 명시적 컬럼 + `owner_name` JOIN + `COUNT(*) OVER() AS total_count`로
  바꾸고 기존 `CollectionRow` 프로젝션을 재사용한다(신규 타입 추가 없음).
- `LIMIT :limit OFFSET :offset` 추가.
- `COUNT(*) OVER()`는 반환된 행 위에만 얹혀 계산되므로, 요청한 페이지가 결과 범위를
  넘어가면(예: 자식이 3개뿐인데 `page=5`로 요청) 행 자체가 없어 전체 개수를 알 수 없다.
  `findReadableCollections` 때와 동일한 이유로 `countReadableChildren` 폴백을 처음부터
  같이 추가했다(#240에서는 이 케이스를 CodeRabbit 리뷰에서 사후에 발견했었다).

---

## 해결 (구현)

### 1. `CollectionRepository.findReadableChildren` — 프로젝션 + 페이지네이션으로 전환

```java
@Query(value = """
        WITH RECURSIVE parent_ancestors AS (
            SELECT id, parent_collection_id FROM collections WHERE id = :parentId
            UNION ALL
            SELECT c.id, c.parent_collection_id
            FROM collections c
            JOIN parent_ancestors a ON c.id = a.parent_collection_id
        )
        SELECT
            c.id AS collection_id, c.name AS name, c.description AS description,
            c.owner_user_id AS owner_user_id, u.name AS owner_name,
            c.parent_collection_id AS parent_collection_id,
            c.visibility AS visibility, c.status AS status, c.created_at AS created_at,
            COUNT(*) OVER() AS total_count
        FROM collections c
        JOIN users u ON u.id = c.owner_user_id
        WHERE c.parent_collection_id = :parentId AND c.status = 'ACTIVE'
          AND ( ... 기존 owner/PUBLIC/USER/ROLE/DEPARTMENT 조건 그대로 ... )
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
List<CollectionRow> findReadableChildren(
        @Param("parentId") Long parentId, @Param("userId") Long userId,
        @Param("limit") int limit, @Param("offset") long offset);
```

부서 권한 조건의 서브쿼리 alias가 바깥 `JOIN users u`(owner 조인)와 겹쳐서
`u` → `du`(department user)로 이름을 바꿨다 — 동작은 그대로이고 alias 충돌만 해소.

### 2. `countReadableChildren` — 빈 페이지 폴백 신규 추가

`findReadableChildren`과 동일한 `WHERE` 조건에 `SELECT COUNT(*)`만 남긴 버전.
두 쿼리는 조건이 어긋나면 안 되므로 나란히 유지한다.

### 3. `CollectionQueryService.getChildren` — `PageResponse<CollectionResponse>` 반환

```java
public PageResponse<CollectionResponse> getChildren(Long userId, Long collectionId, int page, int size) {
    DocumentCollection parent = collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
    if (!permissionQueryService.canReadCollection(userId, parent)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }
    Pageable pageable = PageRequest.of(page, size, COLLECTION_SORT);
    List<CollectionRow> rows = collectionRepository.findReadableChildren(
            collectionId, userId, pageable.getPageSize(), pageable.getOffset());
    long totalElements = rows.isEmpty()
            ? collectionRepository.countReadableChildren(collectionId, userId)
            : rows.get(0).getTotalCount();
    List<CollectionResponse> content = rows.stream().map(collectionConverter::toResponse).toList();
    Page<CollectionResponse> resultPage = new PageImpl<>(content, pageable, totalElements);
    return PageResponse.from(resultPage, content);
}
```

부모 조회 + 부모 자신에 대한 `canReadCollection` 권한 확인은 이번 수정 대상이 아니라
그대로 남아있다 — 이번에 바뀐 건 그 뒤에 이어지는 자식 조회/변환 부분이다.

### 4. `CollectionController.getChildren` — `page`/`size` 파라미터 추가

`GET /collections`와 동일한 계약(`page` 기본 0, `size` 기본 20 · `@Min(1)` `@Max(100)`)으로 맞췄다.

```java
@GetMapping("/{collectionId}/children")
public ResponseEntity<ApiResponse<PageResponse<CollectionResponse>>> getChildren(
        @PathVariable Long collectionId,
        @Parameter(hidden = true) @CurrentUser Long userId,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ResponseUtils.ok(collectionQueryService.getChildren(userId, collectionId, page, size));
}
```

### 5. `CollectionConverter` — 단건 `toResponse(DocumentCollection)` 오버로드 제거

N+1의 원인이던 오버로드(자식마다 `owner.getName()`을 지연 로딩으로 채우던 경로)가
더 이상 호출되지 않아 삭제했다. `toResponse(DocumentCollection, String ownerName)`(단건
상세·생성 시 owner 하나만 lazy-load)과 `toResponse(CollectionRow)`(목록·자식, 쿼리에서
owner_name을 이미 채워서 넘김) 두 오버로드만 남는다.

---

## API 계약 변경

```
Before: GET /collections/{id}/children
        → 200 OK, List<CollectionResponse> (전체, 개수 제한 없음)

After:  GET /collections/{id}/children?page=0&size=20
        → 200 OK, PageResponse<CollectionResponse>
          { content, page, size, totalElements, totalPages, first, last }
```

프론트가 이 응답 형태를 반영하기 전까지는 배포 순서에 주의가 필요해 백엔드/프론트를
같은 PR에 포함했다.

---

## 프론트엔드 (`frontend/app/features/CollectionsPage.tsx`)

- `apiRequest<Collection[]>(...)` → `apiRequest<PageResponse<Collection>>("...&size=100")`.
  페이지 이동 UI는 이번 스코프에 넣지 않고, `size`를 넉넉히(100) 잡아 한 번에 받는 방식을
  택했다 — 자식 컬렉션 목록은 컬렉션 상세 화면 안의 보조 섹션이라, 문서 목록(`collectionDocuments`)처럼
  독립된 페이지네이션 UI를 붙일 만큼의 비중은 아니라고 판단했다. 자식이 100개를 넘는 경우의
  페이지 컨트롤은 후속 이슈로 미룬다.
- `children` 상태를 `Collection[]` → `PageResponse<Collection> | null`로 바꾸고, 렌더링부는
  `children.content.map(...)` / `children.content.length`로 `.content`를 거치도록 수정.
- 168번 줄 주석("children은 현재 사용자가 읽을 수 있는 직계 자식만 담고 있어 실제 하위 컬렉션
  존재 여부의 기준이 될 수 없다")은 페이지네이션 도입 후에도 그대로 유효하다 — 여전히 "내가
  읽을 수 있는 자식"만 담고 있고, `size=100`을 넘는 경우 첫 페이지만 담는다는 제약이 오히려
  하나 더 생겼을 뿐이라 별도 수정하지 않았다.

---

## 로컬 검증

### 자동 테스트

`./backend/gradlew -p backend test --tests "com.opensource.docgrid.domain.collection.*" --tests "com.opensource.docgrid.domain.permission.*"` → `BUILD SUCCESSFUL` (60개)

- `CollectionQueryServiceTest`: `getChildren_returnsPagedResponses_when_parentIsReadable`,
  `getChildren_returnsAccurateTotalElements_whenPageBeyondLastPage`(신규 — 빈 페이지
  폴백 검증), `getChildren_throws_when_parentReadIsDenied`(갱신)
- `CollectionTreeRepositoryTest`: `readableChildrenIds()` 헬퍼 추가, 기존 케이스 3개를
  `findReadableChildren`의 새 시그니처로 전환, `findReadableChildren_paginatesAndReturnsTotalCountAndOwnerName`(신규),
  `countReadableChildren_returnsTotalCount_whenPageBeyondLastPage`(신규)
- `CollectionControllerTest`: `getChildren_returnsChildCollectionPage`(갱신),
  `getChildren_returnsBadRequest_whenPageInputIsInvalid`(신규)

### 수동 검증 — 로컬 Postgres + 실행 중인 서버에 직접 API 호출

부모 컬렉션 1개 + 자식 5개를 만들어 확인했다.

| 요청 | 응답 |
|---|---|
| `page=0&size=2` | content 2건, `totalElements=5`, `totalPages=3`, `first=true`, `last=false` |
| `page=1&size=2` | content 2건, 나머지 필드 동일 패턴 |
| `page=2&size=2` | content 1건(마지막 페이지), `last=true` |
| `page=5&size=2`(마지막 페이지 초과) | content 0건, **`totalElements=5`(정확)**, `last=true` |
| 부모를 읽을 권한이 없는 사용자로 호출 | `403 PERMISSION_DENIED` |

`page=5` 케이스가 `countReadableChildren` 폴백이 제대로 동작하는지 보여주는 지점이다
(폴백이 없었다면 #240에서 발견됐던 것과 같은 이유로 `totalElements=0`이 나왔을 것).

프론트엔드도 dev 서버로 직접 로그인 → 컬렉션 생성 → 하위 컬렉션 3개 생성 →
상세 페이지에서 카드로 렌더링 → 손자 컬렉션까지 drill-down → 하위 컬렉션 없는
빈 상태까지 브라우저에서 확인했다.

---

## 설계 결정 요약

- `findReadableCollections`(#240)와 동일한 `COUNT(*) OVER()` + 빈 페이지 폴백 패턴을
  그대로 재사용 — 새로운 패턴을 만들지 않았다.
- `parent_ancestors` CTE는 앵커가 있는 쿼리라(`WHERE id = :parentId`) #312에서 closure
  table로 바꾼 대상(앵커 없는 `collection_ancestors`)과 다르다 — 이번 스코프에서 건드리지
  않았다.
- 프론트는 페이지 이동 UI 없이 `size=100`으로 한 번에 받는 방식을 택했다 — 자식 목록은
  컬렉션 상세의 보조 섹션이라 문서 목록만큼의 UI 비중을 주지 않았다.

## 남은 이슈 / TODO

- 자식이 100개를 넘는 컬렉션에 대한 프론트 페이지 컨트롤은 이번 스코프가 아니다.
- `findReadableChildren`의 EXISTS 서브쿼리들이 실제 실행계획에서 InitPlan으로 한 번만
  평가되는지는 #240 때와 마찬가지로 별도 확인하지 않았다.

## 다음 단계

머지 후 `docs/test-results/`에 테스트 결과 문서 별도 작성(`docs-management.md` 컨벤션).
[[project_collection_list_pagination_perf_debt]] 메모리를 "해결됨"으로 갱신.
