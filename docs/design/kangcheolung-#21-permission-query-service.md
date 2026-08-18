# #21 PermissionQueryService — 통일된 권한 판단 로직

closes #21

---

## 배경

`#16`(컬렉션 CRUD), `#18`(권한 부여/회수)까지 만들면서 이미 "권한이 있는지 확인하는 로직"이 `canWriteCollection`, `canAdminCollection` 같은 형태로 여러 곳에서 필요해졌다. 이 이슈는 그 판단 로직을 **`PermissionQueryService` 하나로 통일**해서, 이후 모든 권한 확인(컬렉션 CRUD, 권한 부여/회수, RAG 블록의 검색 pre-filter/live check까지)이 이 서비스만 거치도록 만든다.

이 서비스는 이후 검색 블록(F-SEARCH)의 권한 pre-filter/live check(`AccessibleDocumentQueryService`, `SearchFacade`)에서도 그대로 재사용된다 — RAG 블록 설계 문서들에 "`PermissionQueryService.canReadDocument()`를 재사용" 형태로 여러 번 인용된 바로 그 서비스다.

---

## 설계 원칙

### 비대칭 캐싱 (`#18`에서 만든 캐시를 판단 로직에서 어떻게 쓰는지)

```text
USER 권한  → user_document_access_cache 조회 (빠름, index 조회 1번)
ROLE 권한  → 매 요청마다 live 조회 (JOIN 여러 번)
DEPT 권한  → 매 요청마다 live 조회 (JOIN 여러 번)
```

### 단락 평가 (short-circuit)

`canReadDocument` 같은 boolean 판단 메서드는 앞 단계에서 이미 `true`가 나오면 이후 단계 쿼리를 실행하지 않는다 — 소유자(1단계)면 2~5단계 쿼리가 아예 안 나간다.

**주의**: 이 단락 평가 원칙은 `#24`의 `checkDocumentPermission()`에는 그대로 적용되지 않는다 — read/write/admin을 **동시에** 알아야 해서 OWNER가 아닌 이상 모든 단계를 끝까지 탐색해야 한다(`#24` 참고).

---

## 신규 파일

### `domain/permission/service/query/PermissionQueryService.java`

이 이슈의 핵심 파일이며, 6개의 권한 판정 그룹(`canReadDocument`/`canWriteDocument`/`canAdminDocument`/`canReadCollection`/`canWriteCollection`/`canAdminCollection`, `canReadCollection`은 이후 리팩토링에서 추가됨 — 아래 참고)을 제공한다. 컬렉션 대상 3종은 각각 ID로 조회하는 버전과, 이미 조회된 `DocumentCollection` 엔티티를 받는 버전 2개씩 오버로드로 제공해서 — 문서 3종(오버로드 없음) + 컬렉션 3종(오버로드 2개씩)으로 공개 메서드 시그니처는 총 9개다. 호출부가 이미 엔티티를 들고 있으면 중복 조회 없이 엔티티 버전을 바로 쓸 수 있다.

#### `canReadDocument` (5단계 → **2026-08-18부터 6단계**, 아래 참고)

```java
public boolean canReadDocument(Long userId, Long documentId) {
    Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

    // 1단계: 소유자
    if (document.getOwner().getId().equals(userId)) {
        return true;
    }
    // 2단계: PUBLIC
    if (document.getVisibility() == VisibilityType.PUBLIC) {
        return true;
    }
    // 3단계: USER 캐시
    if (cacheRepository.existsValidReadCache(userId, documentId)) {
        return true;
    }
    // 4단계: ROLE live (문서 직접 권한 OR 컬렉션 경유 권한)
    if (documentPermissionRepository.existsRoleReadPermission(userId, documentId)
            || collectionPermissionRepository.existsRoleReadPermissionForDocument(userId, documentId)) {
        return true;
    }
    // 5단계: DEPARTMENT live
    if (documentPermissionRepository.existsDeptReadPermission(userId, documentId)
            || collectionPermissionRepository.existsDeptReadPermissionForDocument(userId, documentId)) {
        return true;
    }
    return false;
}
```
(실제 코드에는 각 단계 사이에 `System.nanoTime()` 기반 타이밍 측정과 `log.info()`가 끼어있다 — 아래 "성능 측정 로그" 참고. 여기서는 판단 로직만 발췌했다.)

**4/5단계가 매번 OR로 문서 직접 권한과 컬렉션 경유 권한을 같이 조회하는 이유**: 기본 권한은 컬렉션 단위(`#16`)로 부여되므로, "이 문서가 속한 컬렉션에 ROLE 권한이 있는지"도 확인해야 한다. `collectionPermissionRepository.existsRoleReadPermissionForDocument()`가 `CollectionPermission JOIN CollectionDocument`로 이걸 처리한다(아래 리포지토리 절 참고).

**(2026-08-18 추가, 이슈 #229) 6단계: 부모 컬렉션 체인 상속**. 5단계까지 통과 못 하면, 이 문서가 속한 컬렉션(들)의 **조상 컬렉션**에 ROLE/DEPARTMENT 권한이 있는지까지 확인한다:
```java
// 6단계: 부모 컬렉션 체인 상속 (ROLE/DEPARTMENT)
List<Long> effectiveCollectionIds = collectionRepository.findEffectiveCollectionIdsForDocument(documentId);
if (!effectiveCollectionIds.isEmpty()
        && (collectionPermissionRepository.existsRoleReadPermissionForCollections(userId, effectiveCollectionIds)
                || collectionPermissionRepository.existsDeptReadPermissionForCollections(userId, effectiveCollectionIds))) {
    return true;
}
```
`findEffectiveCollectionIdsForDocument()`는 `WITH RECURSIVE`로 "이 문서가 속한 컬렉션 전체(N:M) + 그 각각의 조상 전체"를 한 번에 구하는 native 쿼리다. 상세 설계는 `docs/design/kangcheolung-#229-collection-tree.md` 참고. `canWriteDocument`/`canAdminDocument`/`checkDocumentPermission`(`#24`)에도 동일하게 6단계가 추가됐다 — 기존 5단계 로직은 한 줄도 안 건드리고 끝에 새 블록만 이어붙이는 방식으로 넣었다(기존 `PermissionQueryServiceTest` 무변경 통과 확인됨).

#### `canWriteDocument` / `canAdminDocument` (각 4단계 → **2026-08-18부터 5단계**)

`canReadDocument`와 거의 같은 구조이지만 **PUBLIC 단계가 없다** — PUBLIC은 읽기만 허용하는 개념이라 쓰기/관리 권한 판단에는 끼어들 자리가 없다. 그래서 1단계(소유자) → 2단계(USER 캐시) → 3단계(ROLE) → 4단계(DEPT), 총 4단계였다. 여기에도 위와 동일한 "5단계: 부모 컬렉션 체인 상속"이 이슈 #229에서 추가됐다(`existsRoleWrite/AdminPermissionForCollections`, `existsDeptWrite/AdminPermissionForCollections` 사용).

#### `canReadCollection` / `canWriteCollection` / `canAdminCollection`

```java
// ID로 조회 후 엔티티 버전에 위임 — soft-delete된 컬렉션은 걸러진다
public boolean canWriteCollection(Long userId, Long collectionId) {
    return canWriteCollection(userId, getActiveCollection(collectionId));
}

// 이미 조회된 엔티티로 판단 — 호출부가 이미 non-deleted 엔티티임을 보장해야 함
public boolean canWriteCollection(Long userId, DocumentCollection collection) {
    if (collection.getOwner().getId().equals(userId)) return true;
    Long collectionId = collection.getId();
    if (collectionPermissionRepository.existsUserWritePermission(userId, collectionId)) return true;
    if (collectionPermissionRepository.existsRoleWritePermissionForCollection(userId, collectionId)) return true;
    if (collectionPermissionRepository.existsDeptWritePermissionForCollection(userId, collectionId)) return true;

    // (2026-08-18 추가, 이슈 #229) 부모 컬렉션 체인 상속 — 조상 ID 전체를 대상으로 재검사
    List<Long> ancestorIds = collectionRepository.findAncestorIdsInclusive(collectionId);
    if (collectionPermissionRepository.existsRoleWritePermissionForCollections(userId, ancestorIds)) return true;
    return collectionPermissionRepository.existsDeptWritePermissionForCollections(userId, ancestorIds);
}

private DocumentCollection getActiveCollection(Long collectionId) {
    return collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
}
```
`canReadCollection`은 위와 동일한 구조에 **PUBLIC 단계**만 추가된다(`canReadDocument`의 2단계와 동일하게, `collection.getVisibility() == VisibilityType.PUBLIC`이면 소유자/권한 여부와 무관하게 허용). `canAdminCollection`도 같은 뼈대(대상 권한 종류만 다름)다.

컬렉션 판단에는 **캐시가 없다** — `user_document_access_cache`는 문서 단위 캐시라 컬렉션 자체에 대한 캐시 개념이 없다. 그래서 OWNER, (READ의 경우 PUBLIC), USER 직접 권한, ROLE, DEPT, (2026-08-18부터) 부모 컬렉션 상속을 매번 순서대로 live 조회한다. `#16`의 `addDocument()`/`getCollection()`, `#18`의 `grantPermission()`이 엔티티 버전을 호출해 중복 조회 없이 이 메서드들을 재사용한다.

### `domain/permission/repository/CollectionPermissionRepository.java` — 문서→컬렉션 경유 JOIN 쿼리

```java
// ROLE live — 사용자 역할 기반 컬렉션→문서 읽기 권한 존재 여부
@Query("""
        SELECT COUNT(cp) > 0 FROM CollectionPermission cp
        JOIN CollectionDocument cd ON cd.collection = cp.collection
        JOIN UserRole ur ON ur.role = cp.role
        WHERE cd.document.id = :documentId
          AND cp.targetType = ...ROLE
          AND ur.user.id = :userId
          AND cp.canRead = true
          AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
        """)
boolean existsRoleReadPermissionForDocument(@Param("userId") Long userId, @Param("documentId") Long documentId);
```
`CollectionPermission → CollectionDocument → 대상 문서` 경로를 JOIN 하나로 처리한다 — "이 문서가 속한 어떤 컬렉션에, 이 사용자가 속한 역할에 대한 READ 권한이 있는가"를 SQL 레벨에서 한 번에 판단한다. 문서 판단(`*ForDocument` 접미사가 붙은 메서드)과 컬렉션 자체 판단(접미사 없는 `*ForCollection` 메서드) 총 12개의 `existsXxx` 메서드가 이 리포지토리에 있다.

**(2026-08-18 추가, 이슈 #229)** 여기에 `existsRole/DeptRead/Write/AdminPermissionFor**Collections**`(복수형, `List<Long> collectionIds`를 받는 버전) 6개가 더 생겨서 총 18개가 됐다. 단일 ID(`Long`)를 받는 기존 12개는 시그니처를 그대로 유지했고(치환하지 않음), 신규 6개를 "조상 ID 리스트"를 받는 용도로 추가만 했다 — 기존 12개를 치환했다면 이 메서드들을 스텁하는 기존 테스트(`PermissionQueryServiceTest` 등)가 대량으로 깨졌을 것이라, 추가(append) 방식으로 리스크를 낮췄다.

### `domain/permission/repository/DocumentPermissionRepository.java` — 문서 직접 권한 JOIN

`CollectionPermissionRepository`의 `*ForDocument` 메서드들과 대응하지만, 컬렉션 경유 없이 `document_permissions`를 바로 조회한다(ROLE/DEPT 조합 6개).

### `domain/permission/repository/UserDocumentAccessCacheRepository.java` — 캐시 조회 3종

```java
@Query("""
        SELECT COUNT(c) > 0 FROM UserDocumentAccessCache c
        WHERE c.user.id = :userId AND c.document.id = :documentId
          AND c.canRead = true
          AND c.invalidatedAt IS NULL
          AND (c.expiresAt IS NULL OR c.expiresAt > CURRENT_TIMESTAMP)
        """)
boolean existsValidReadCache(@Param("userId") Long userId, @Param("documentId") Long documentId);
```
"유효한 캐시"의 정의가 이 쿼리 하나에 응축되어 있다: `invalidatedAt IS NULL`(무효화 안 됨) **그리고** `expiresAt`이 없거나 아직 안 지남. 두 조건을 다 만족해야 한다.

---

## 성능 측정 로그

각 단계 진입/이탈 시점을 `System.nanoTime()`으로 재서 ms 단위로 로그를 남긴다.

```java
long t2 = System.nanoTime();
double step1Ms = (t2 - t1) / 1_000_000.0;   // 다음 단계 실행 전에 미리 계산
if (cacheRepository.existsValidReadCache(...)) { ... }
```

**시각 계산이 "다음 단계 실행 전"에 오는 이유**: `step1Ms`를 로그로 찍는 시점은 2단계 쿼리가 이미 끝난 뒤인데, 그 값 자체는 1단계에 걸린 시간이어야 한다. 그래서 다음 단계 쿼리를 실행하기 **직전**에 이전 구간 소요시간을 먼저 계산해서 변수에 담아두고, 로그 출력 시점(이후 단계가 다 끝난 뒤일 수도 있음)과 무관하게 정확한 구간별 시간이 남도록 했다.

실제 로그 예시 (`docs/test-results/kangcheolung-#21-permission-query-service.md`에서 발췌):
```text
[PERM] checkDoc owner doc=1 user=2 elapsed=7.94ms
[PERM] checkDoc doc=2 user=3 canRead=true canWrite=false canAdmin=false sources=[PUBLIC] elapsed=33.446208ms
[PERM] checkDoc doc=1 user=3 canRead=true canWrite=false canAdmin=false sources=[USER_CACHE, ROLE] elapsed=26.229709ms
```

---

## 로컬 검증 (Swagger 수동 테스트 — 실제 수행 기록)

`docs/test-results/kangcheolung-#21-permission-query-service.md` 4.1~4.3절에서, OWNER(1단계 즉시 반환) / PUBLIC(2단계) / USER_CACHE(3단계)가 실제 로그의 `elapsed` 값과 함께 각각 확인됐다. 4.5~4.7절에서는 ROLE(4단계), 캐시 회수 후 ROLE 경로 유지, 컬렉션에서 문서 제거 시 ROLE 경로 차단까지 5단계 전체가 실제 시나리오로 확인됐다(전체 시나리오는 `#24` 문서에서 더 자세히 다룸 — `checkDocumentPermission()`을 호출하는 `/me` API 기준 기록이라서).

### 자동 테스트

```bash
$ ./gradlew test --tests "*PermissionQueryServiceTest*"
BUILD SUCCESSFUL
```
`PermissionQueryServiceTest` 54개 모두 통과(2026-08-18 기준 재검증, 이슈 #229에서 부모 컬렉션 상속 케이스 7개 추가돼 47→54) — 이 서비스의 권한 판정 그룹 6개(`canReadDocument`, `canWriteDocument`, `canAdminDocument`, `canReadCollection`, `canWriteCollection`, `canAdminCollection`, 오버로드 포함 공개 메서드 시그니처 9개) 각각의 단계별 분기를 검증하는 테스트가 다수 포함되어 있다.

---

## 에러 케이스 정리

| 상황 | HTTP | 코드 |
|---|---:|---|
| 문서 없음 | 404 | `DOCUMENT-001` |
| 컬렉션 없음 | 404 | `COLLECTION-001` |

권한이 없는 경우 자체는 에러가 아니다 — `canXxx()` 메서드는 예외를 던지지 않고 그냥 `false`를 반환한다. 호출한 쪽(`#16`의 `addDocument()` 등)이 `false`를 보고 `PERMISSION_DENIED`(403)를 던질지 말지 결정한다.

---

## 설계 결정 요약

**판단 로직을 서비스 하나로 통일**: `canReadDocument`/`canWriteCollection` 등 여러 메서드가 있지만 전부 `PermissionQueryService`라는 한 클래스에 모여있다. 권한 판단 규칙이 여러 서비스에 흩어지면 나중에 규칙을 하나 바꿀 때(예: 캐시 조건 변경) 여러 곳을 찾아 고쳐야 한다. 한 곳에 모아두면 이런 위험이 없다. RAG/검색 블록이 이 서비스를 그대로 재사용할 수 있었던 것도 이 통일 덕분이다.

**성능 순서(빠른 것 → 느린 것)로 단계를 배치**: 소유자 체크(FK 비교 1번) → PUBLIC 체크(컬럼 비교) → 캐시 조회(인덱스 1방) → ROLE/DEPT live(JOIN 여러 번). 대부분의 실제 요청은 앞쪽 단계에서 끝나길 기대하고 순서를 이렇게 잡았다 — 단락 평가와 맞물려 "흔한 케이스일수록 빠르게 끝난다"는 성질을 만든다.

**컬렉션 판단에는 캐시를 두지 않음**: `user_document_access_cache`가 애초에 문서 단위로 설계되어 있어서, 컬렉션 자체에 대한 접근 여부는 캐시할 방법이 없다(캐시하려면 별도 테이블이 필요했을 것). 컬렉션 판단은 상대적으로 호출 빈도가 낮다고 보고(문서 접근이 압도적으로 잦음) live 조회만으로 충분하다고 판단한 것으로 보인다.

**(추가) `canReadCollection` 도입 + ID/엔티티 오버로드 분리**: `getCollection()`(`#16`) 단건 조회에 권한 체크가 전혀 없던 게 확인되어 `canReadCollection`(OWNER→PUBLIC→USER→ROLE→DEPT)을 추가했다. 동시에 `getCollection()`/`addDocument()`/`grantPermission()`이 컬렉션을 조회한 뒤 권한 메서드를 ID로 다시 호출해 같은 row를 두 번 SELECT하던 문제와, `findById()`가 soft-delete(`status=DELETED`)를 걸러내지 않던 문제가 함께 발견되어, 컬렉션 대상 3개 메서드를 "ID 버전(내부에서 조회+status 필터 후 위임) + 엔티티 버전(조회 없이 판단)"으로 나눠 한 번에 해결했다.

---

## 남은 이슈 / TODO

- 나노초 기반 성능 로그가 프로덕션에서도 항상 `log.info()`로 남는다 — 트래픽이 많아지면 로그량 자체가 부담일 수 있어 로그 레벨 조정이나 샘플링이 필요할 수 있다.
- `canReadDocument`류 메서드들 사이에 문서 조회(`documentRepository.findById`)가 메서드마다 중복된다 — 셋 다 필요하면(예: `checkDocumentPermission`처럼) 문서 조회를 한 번만 하고 넘기는 내부 메서드로 리팩터링할 여지가 있다. **(참고)** 컬렉션 쪽(`canReadCollection`/`canWriteCollection`/`canAdminCollection`)은 이미 "ID 버전 + 엔티티 버전" 오버로드로 이 문제를 해결했다 — 문서 쪽도 같은 패턴을 그대로 적용하면 된다(별도 후속 작업으로 분리, 이번 라운드는 컬렉션만 처리).

## 이후 업데이트 (2026-08-18, 이슈 #229 — 컬렉션 트리)

컬렉션 트리 기능이 추가되면서 6개 권한 판정 그룹 전부에 "부모 컬렉션 체인 상속" 단계가 하나씩 더 생겼다(위 각 절에 인라인으로 반영). 핵심 요약:

- 문서 판단 4종(`canReadDocument`/`canWriteDocument`/`canAdminDocument`/`checkDocumentPermission`)은 `CollectionRepository.findEffectiveCollectionIdsForDocument()`(문서가 속한 컬렉션+그 조상 전체)를 마지막 단계로 추가.
- 컬렉션 판단 3종(`canReadCollection`/`canWriteCollection`/`canAdminCollection`)은 `CollectionRepository.findAncestorIdsInclusive()`(자기 자신+조상 전체)를 마지막 단계로 추가.
- 전부 **기존 로직은 안 건드리고 끝에 새 단계만 이어붙이는 방식**으로 넣었다 — 대규모 기존 테스트(`PermissionQueryServiceTest` 817줄, 원래 47개 케이스)를 한 줄도 안 고치고 그대로 통과시키기 위한 선택.
- 컬렉션 목록(`GET /collections`)도 이번에 owner-only에서 "읽을 수 있는 전체"로 넓어졌는데, 그건 `PermissionQueryService`가 아니라 `CollectionRepository.findReadableCollectionIds()`라는 별도 native 쿼리로 구현했다 — 6개 판정 그룹과는 별개 경로다.

상세 설계는 신규 문서 `docs/design/kangcheolung-#229-collection-tree.md` 참고.

## 다음 단계

`#24`(문서 권한 확인 API — 이 서비스의 `checkDocumentPermission()`을 노출), `#29`(컬렉션 관리 API)로 이어진다. 이후 RAG 블록의 `AccessibleDocumentQueryService`(권한 pre-filter)와 `SearchFacade`(live check)가 이 서비스의 `canReadDocument()`를 그대로 재사용한다. 이후 `#229`(컬렉션 트리)로 이어진다.
