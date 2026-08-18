# #240 컬렉션 목록/자식 조회 — 권한 필터링을 앱단이 아니라 SQL에서 처리

closes #240

---

## 배경

이슈 #229(컬렉션 트리) 구현 완료 후 코드래빗 리뷰 대응 과정에서, `GET /collections`와
`GET /collections/{id}/children` 두 API가 권한 필터링을 DB 쿼리 한 번에 끝낼 수 있는데도
애플리케이션 코드가 대신 반복/재조회를 하고 있다는 걸 발견했다. 지금 컬렉션 규모(수십~수백
개 추정)에선 체감이 없어서 한동안 백로그로 남겨뒀다가, 이번에 정식으로 고쳤다.

---

## 문제상황

### 문제 1 — GET /collections (컬렉션 목록)

`CollectionQueryService.getCollections()`가 페이지 하나(예: 20개)를 보여주기 위해
쿼리를 2단계로 나눠서 불렀다.

```java
// 수정 전
public PageResponse<CollectionResponse> getCollections(Long userId, String keyword, int page, int size) {
    Pageable pageable = PageRequest.of(page, size, COLLECTION_SORT);
    List<Long> readableIds = collectionRepository.findReadableCollectionIds(userId, keyword); // ① 전체 ID
    if (readableIds.isEmpty()) {
        return PageResponse.from(Page.empty(pageable), List.of());
    }
    Page<DocumentCollection> collections = collectionRepository.findAllByIdIn(readableIds, pageable); // ② 그중 20개
    ...
}
```

①번 쿼리는 owner/PUBLIC/USER 직접권한/ROLE/DEPARTMENT 5개 조건을 `UNION`으로 묶고,
부모 컬렉션 상속 판단을 위해 `WITH RECURSIVE collection_ancestors`까지 쓰는데, `LIMIT`이
없어서 "이 사용자가 읽을 수 있는 컬렉션"을 페이지 크기와 무관하게 전부 계산해서
`List<Long>`으로 돌려줬다. ②번 쿼리가 그 리스트를 `WHERE id IN (:ids)`로 다시 조회해서
20개로 잘랐다.

사용자가 읽을 수 있는 컬렉션 수(N)가 늘어날수록, 페이지 크기와 무관하게 N에 비례해서
전송량·서버 메모리·`IN` 절 크기가 커지는 구조였다.

### 문제 2 — GET /collections/{id}/children (직계 자식 조회)

`CollectionQueryService.getChildren()`이 자식을 전부 가져온 뒤, 자식마다 권한 판단
함수를 반복 호출했다(N+1 쿼리 패턴).

```java
// 수정 전
return collectionRepository.findAllByParentCollectionIdAndStatus(collectionId, CollectionStatus.ACTIVE)
        .stream()
        .filter(child -> permissionQueryService.canReadCollection(userId, child)) // 자식마다 반복
        .map(collectionConverter::toResponse)
        .toList();
```

`canReadCollection` 내부는 자식 하나당 최악의 경우 쿼리 6개(직접 USER/ROLE/DEPARTMENT
권한 3개 + 조상 체인 조회 1개 + 조상 ROLE/DEPARTMENT 확인 2개)까지 나갔다. 자식이 M개면
최악의 경우 요청 하나에서 최대 6M개 쿼리가 발생할 수 있었다.

이 문제는 1번과 달리, 권한 판단에 필요한 최소 계산이 아니라 SQL 조건 하나로 대체 가능한
걸 코드에서 반복하고 있던 순수한 낭비였다.

---

## 설계

### 문제 1 해결 설계

Spring Data의 `Page<T>` + 별도 `countQuery` 조합은 일부러 쓰지 않았다. 이 방식은 콘텐츠
쿼리와 count 쿼리가 각각 독립 실행되는데, 둘 다 내부에서 재귀 CTE를 처음부터 다시
계산한다 — 즉 재귀 계산이 원래 1번(①번 쿼리)만 돌던 게, 순진하게 "Page+countQuery"로
바꾸면 오히려 1번→2번으로 늘어난다. 대신 `COUNT(*) OVER()` 윈도우 함수로 한 쿼리 안에서
콘텐츠와 총개수를 동시에 계산하도록 설계했다.

Spring Data JPA는 이 형태(entity 컬럼 + 윈도우 함수로 얹은 추가 컬럼)를 `Page<Entity>`로
자동 매핑해주지 못하므로, `total_count` 필드가 있는 프로젝션 인터페이스(`CollectionRow`)로
결과를 받아서 서비스 레이어에서 `new PageImpl<>(content, pageable, totalCount)`으로 직접
조립하는 방식으로 설계했다.

### 문제 2 해결 설계

같은 부모 밑의 자식들은 "부모(및 그 위 조상들)로부터 상속받는 ROLE/DEPARTMENT 권한이
있는지"를 전부 똑같이 공유한다 — 자식마다 다시 계산할 필요가 없다. 이 부분을 `WITH
RECURSIVE parent_ancestors`로 부모 기준 한 번만 계산하고, 자식별로 다른 부분
(owner/PUBLIC/자기 자신에게 직접 부여된 권한)만 자식마다 `EXISTS` 조건으로 뒀다. 참고로
`parent_ancestors`를 참조하는 `EXISTS` 서브쿼리는 바깥쪽 `c`(자식 행)와 상관관계가 없는
비상관 서브쿼리라, PostgreSQL이 쿼리당 한 번만 평가하도록 최적화해주는 경우가 일반적이다
(InitPlan) — 다만 이 최적화 여부와 무관하게 결과의 정확성은 항상 보장된다.

---

## 해결 (구현)

### 1. `CollectionRow.java` — 신규 프로젝션 인터페이스

```java
public interface CollectionRow {
    Long getCollectionId();
    String getName();
    String getDescription();
    Long getOwnerUserId();
    Long getParentCollectionId();
    String getVisibility();
    String getStatus();
    LocalDateTime getCreatedAt();
    Long getTotalCount();
}
```

기존 `VectorSearchRow`(pgvector 검색 결과 프로젝션)와 동일한 컨벤션 — 컬럼 alias가
snake_case면 Spring Data JPA가 camelCase getter로 자동 매핑해준다.

### 2. `CollectionRepository.java` — `findReadableCollections` / `findReadableChildren` 신규

`findReadableCollectionIds`(전체 ID 조회) + `findAllByIdIn`(재조회), `findAllByParentCollectionIdAndStatus`(조건 없는 전체 자식 조회) 세 메서드를 삭제하고 대체했다.

```java
@Query(value = """
        WITH RECURSIVE collection_ancestors AS (
            SELECT id AS collection_id, id AS ancestor_id FROM collections
            UNION ALL
            SELECT ca.collection_id, c.parent_collection_id AS ancestor_id
            FROM collection_ancestors ca
            JOIN collections c ON c.id = ca.ancestor_id
            WHERE c.parent_collection_id IS NOT NULL
        ),
        readable AS (
            -- 기존 5개 UNION 브랜치(owner/PUBLIC/USER/ROLE/DEPARTMENT) 그대로
            ...
        )
        SELECT
            c.id AS collection_id, c.name AS name, c.description AS description,
            c.owner_user_id AS owner_user_id, c.parent_collection_id AS parent_collection_id,
            c.visibility AS visibility, c.status AS status, c.created_at AS created_at,
            COUNT(*) OVER() AS total_count
        FROM collections c
        JOIN readable r ON r.id = c.id
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
List<CollectionRow> findReadableCollections(
        @Param("userId") Long userId, @Param("keyword") String keyword,
        @Param("limit") int limit, @Param("offset") long offset);
```

```java
@Query(value = """
        WITH RECURSIVE parent_ancestors AS (
            SELECT id, parent_collection_id FROM collections WHERE id = :parentId
            UNION ALL
            SELECT c.id, c.parent_collection_id FROM collections c
            JOIN parent_ancestors a ON c.id = a.parent_collection_id
        )
        SELECT c.* FROM collections c
        WHERE c.parent_collection_id = :parentId AND c.status = 'ACTIVE'
          AND (
            c.owner_user_id = :userId
            OR c.visibility = 'PUBLIC'
            OR EXISTS (... USER 직접권한 ...)
            OR EXISTS (... ROLE 직접권한 ...)
            OR EXISTS (... DEPARTMENT 직접권한 ...)
            OR EXISTS (... parent_ancestors 거쳐서 ROLE 상속 ...)
            OR EXISTS (... parent_ancestors 거쳐서 DEPARTMENT 상속 ...)
          )
        ORDER BY c.created_at DESC, c.id DESC
        """, nativeQuery = true)
List<DocumentCollection> findReadableChildren(@Param("parentId") Long parentId, @Param("userId") Long userId);
```

### 3. `CollectionConverter.java` — `CollectionRow` → `CollectionResponse` 변환 오버로드 추가

```java
// findReadableCollections 네이티브 쿼리 프로젝션 결과를 그대로 변환 (owner 엔티티를 거치지 않음)
public CollectionResponse toResponse(CollectionRow row) {
    return new CollectionResponse(
            row.getCollectionId(), row.getName(), row.getDescription(), row.getOwnerUserId(),
            row.getParentCollectionId(), VisibilityType.valueOf(row.getVisibility()),
            CollectionStatus.valueOf(row.getStatus()), row.getCreatedAt()
    );
}
```

owner 엔티티를 `JOIN FETCH`해서 지연 로딩을 피할 필요가 없어졌다 — `owner_user_id`를
컬럼으로 바로 받기 때문에 엔티티 프록시를 거치지 않는다.

### 4. `CollectionQueryService.java` — 두 메서드 교체

```java
public PageResponse<CollectionResponse> getCollections(Long userId, String keyword, int page, int size) {
    Pageable pageable = PageRequest.of(page, size, COLLECTION_SORT);
    List<CollectionRow> rows = collectionRepository.findReadableCollections(
            userId, keyword, pageable.getPageSize(), pageable.getOffset());
    long totalElements = rows.isEmpty() ? 0 : rows.get(0).getTotalCount();

    List<CollectionResponse> content = rows.stream().map(collectionConverter::toResponse).toList();
    Page<CollectionResponse> resultPage = new PageImpl<>(content, pageable, totalElements);
    return PageResponse.from(resultPage, content);
}

public List<CollectionResponse> getChildren(Long userId, Long collectionId) {
    DocumentCollection parent = collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
    if (!permissionQueryService.canReadCollection(userId, parent)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }
    return collectionRepository.findReadableChildren(collectionId, userId)
            .stream()
            .map(collectionConverter::toResponse)
            .toList();
}
```

`getChildren()`은 `.filter(child -> canReadCollection(...))` 줄이 통째로 사라졌다 — 권한
조건이 리포지토리 쿼리 안으로 옮겨갔기 때문에 서비스는 결과를 DTO로 변환만 한다.

---

## 고도화 결과

| | 수정 전 | 수정 후 |
|---|---|---|
| GET /collections 쿼리 횟수 | 2번(전체 ID 조회 + IN 재조회) | 1번 |
| GET /collections 전송량(N=읽을 수 있는 컬렉션 수) | N에 비례 | 페이지 크기만큼만, N과 무관 |
| GET /collections/{id}/children 쿼리 횟수(M=자식 수) | 1 + 최대 6M | 1 |
| 재귀 CTE(부모 상속 확인) 계산 횟수 | 각 API당 1번 | 동일하게 1번 유지 (콘텐츠+count를 한 쿼리로 합쳐서 유지) |

**재귀 계산 자체(부모 상속 확인 비용)는 이 기능이 존재하는 한 없앨 수 없는 부분이라 그대로
남는다** — 이번 수정으로 없앤 건 그 위에 얹혀있던 불필요한 낭비(N배로 커지는 전송/메모리/
두 번째 쿼리, 자식 개수만큼 반복되던 권한 확인)다.

### 검증 방법 — 회귀 테스트가 실제로 회귀를 잡아내는지 직접 확인

테스트를 작성한 뒤, "이 테스트가 진짜 문제를 잡아내는가"를 확인하려고 일부러 `getChildren()`을
예전 N+1 코드로 되돌려서 테스트를 돌려봤다.

```java
// 일부러 되돌린 코드
return collectionRepository.findReadableChildren(collectionId, userId)
        .stream()
        .filter(child -> permissionQueryService.canReadCollection(userId, child)) // N+1 재현
        .map(collectionConverter::toResponse)
        .toList();
```

결과:
```
Expected size: 3 but was: 0
```
`canReadCollection(userId, child)`가 테스트에서 스텁되지 않은 자식에 대해 기본값
`false`를 반환해서 자식 3개가 전부 걸러졌다 — 처음 작성했던 자식 1개짜리 테스트로는
이 회귀를 못 잡았을 것이다. 테스트를 자식 3개 + `then(permissionQueryService)
.should(times(1)).canReadCollection(...)` 검증으로 보강한 뒤 다시 확인하니, 정상 코드에서는
통과하고 되돌린 코드에서는 실패하는 걸 재확인했다. 코드는 원상복구했다.

---

## 로컬 검증

- `./backend/gradlew -p backend compileJava compileTestJava` 통과
- `CollectionQueryServiceTest`(12개), `CollectionTreeRepositoryTest`(12개, 실제 로컬
  Postgres로 `findReadableCollections`/`findReadableChildren` 검증 — owner/PUBLIC 노출,
  keyword 필터, 페이지네이션(limit/offset 분할과 totalCount 일치), ROLE/DEPARTMENT 부모
  상속(직계 + 조부모 2단계), 자식 권한 필터링/제외 케이스), `CollectionControllerTest`(4개),
  `CollectionCommandServiceTest`(17개) 전부 통과
- collection/permission/document 도메인 전체 테스트(`./gradlew test --tests
  "com.opensource.docgrid.domain.{collection,permission,document}.*"`) 통과

### 신규/변경된 테스트 케이스 (`CollectionTreeRepositoryTest`)

- `findReadableCollections_ownerAndPublic` / `_filtersByKeyword` — 기존 케이스를
  `findReadableCollections` 기준으로 이관
- `findReadableCollections_paginatesAndReturnsTotalCountOnEveryRow` — 컬렉션 3개 생성 후
  limit=2로 두 페이지 조회, 각 페이지 크기(2, 1)와 모든 행의 `totalCount`(=3)가 맞는지,
  두 페이지 사이에 중복이 없는지 검증 (신규)
- `findReadableCollections_inheritsDepartmentPermissionFromParent` / `_inheritsRolePermissionFromParent` — 기존 케이스 이관
- `findReadableChildren_returnsDirectChildrenOnly` — 기존 `findAllByParentCollectionIdAndStatus` 테스트를 이관
- `findReadableChildren_excludesChild_whenNoPermission` — 권한 없는 자식은 제외되는지 (신규,
  기존엔 이 필터링을 서비스 단위 테스트가 mock으로만 검증했는데 실제 DB 쿼리로 검증하도록 보강)
- `findReadableChildren_inheritsRolePermissionFromGrandparent` — 조부모(2단계 위)에 부여된
  ROLE 권한도 상속되는지 (신규, `parent_ancestors`가 직계 부모 1단계만이 아니라 여러 단계를
  타고 올라가는지 검증)

---

## 설계 결정 요약

- **`Page` + `countQuery` 대신 `COUNT(*) OVER()`**: 재귀 CTE 계산이 두 번 되는 걸 피하기
  위해 의도적으로 Spring Data의 일반적인 페이지네이션 패턴을 안 썼다.
- **`parent_ancestors` 서브쿼리를 자식마다 재계산하지 않고 부모 기준 1번만 둠**: 상관관계
  없는(비상관) 서브쿼리라 PostgreSQL이 자동으로 한 번만 평가해주는 걸 기대할 수 있지만,
  이건 최적화일 뿐 정확성의 전제 조건은 아니다.
- **owner 엔티티 `JOIN FETCH` 제거**: `CollectionRow` 프로젝션이 `owner_user_id`를 컬럼으로
  바로 받아서, 엔티티 지연 로딩을 거칠 필요가 없어졌다.

## 남은 이슈 / TODO

- `findReadableChildren`의 EXISTS 서브쿼리 6개가 실제 PostgreSQL 실행계획에서 InitPlan으로
  한 번만 평가되는지는 `EXPLAIN ANALYZE`로 별도 확인하지 않았다 — 정확성엔 영향 없지만, 나중에
  성능을 더 다듬을 필요가 생기면 확인해볼 것.
- [[project_collection_ancestors_cte_perf_risk]]에 기록된 "재귀 CTE가 컬렉션 테이블
  전체를 스캔"하는 이슈는 이번 스코프가 아니다 — `findReadableCollections`의
  `collection_ancestors` CTE는 여전히 범위 제한 없이 전체를 계산한다.

## 다음 단계

머지 후 `docs/test-results/`에 테스트 결과 문서 별도 작성(`docs-management.md`
컨벤션). [[project_collection_list_pagination_perf_debt]] 메모리를 "해결됨"으로 갱신.
