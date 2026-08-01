# #18 권한 부여·회수 및 USER 캐시 설계

closes #18

---

## 배경

`#16`에서 컬렉션 CRUD 기본을 만들었지만, 아직 "누가 이 컬렉션/문서를 볼 수 있는지"를 실제로 부여·회수하는 API가 없었다. 이번 이슈는 그 권한 부여/회수 API와, 검색 pre-filter를 빠르게 만들기 위한 `user_document_access_cache` 갱신 로직을 만든다.

RAG 명세 초반에 이미 정리된 3단계 캐싱 전략(`project_docgrid_specs.md` 메모리 참고)이 여기서 구현된다: **USER 대상 권한만 캐시에 저장**하고, ROLE/DEPARTMENT는 구성원이 동적으로 바뀌므로 캐시하지 않고 매번 live 조회한다.

---

## 핵심 개념

### 기본 권한 vs 예외 권한

```text
기본 권한 단위: collection_permissions
→ 컬렉션에 권한을 부여하면 컬렉션 소속 모든 문서에 적용

예외 권한: document_permissions
→ 특정 문서 하나에만 별도 권한 부여, 최소한으로만 사용
```

### 권한 대상 타입 (`PermissionTargetType`)

```java
public enum PermissionTargetType {
    USER,       // 특정 사용자 개인에게 부여 — 유일하게 캐시 대상
    DEPARTMENT, // 특정 부서 소속 전원 — live 조회
    ROLE        // 특정 역할 전원 — live 조회
}
```

### 권한 종류 (`PermissionType`) → `canRead`/`canWrite`/`canAdmin` 변환

```java
private boolean[] resolvePermissions(PermissionType type) {
    return switch (type) {
        case READ  -> new boolean[]{true, false, false};
        case WRITE -> new boolean[]{true, true, false};
        case ADMIN -> new boolean[]{true, true, true};
    };
}
```
`WRITE`는 `READ`를 포함하고 `ADMIN`은 `WRITE`+`READ`를 모두 포함한다 — 별도로 3개를 다 체크하게 하지 않고, `PermissionType` 하나만 받아서 세 boolean으로 展開하는 방식을 택했다.

### `UserDocumentAccessCache`가 "source of truth가 아닌" 이유

엔티티 Javadoc에 이미 명시되어 있다:

> 이 테이블은 권한의 source of truth가 아니다. 직접 부여된 USER 권한(및 소유권)만 저장하는 검색 pre-filter 가속 캐시일 뿐이며, PUBLIC/ROLE/DEPARTMENT 기반 권한은 여기에 저장하지 않는다. 따라서 이 캐시만으로 최종 접근 허용 여부를 판단해서는 안 되며, 최종 응답을 내려주기 전에는 반드시 live permission check를 거쳐야 한다.

이 원칙이 검색 블록(F-SEARCH)의 "권한 pre-filter(캐시 기반) → live check(정확성 보장)" 2단계 구조로 그대로 이어진다.

**확인된 불일치**: 위 Javadoc은 "소유권(OWNER)도 캐시에 저장된다"고 적고 있고 `AccessSourceType`에도 `OWNER` 값이 정의되어 있지만, 실제로 `AccessSourceType.OWNER`로 캐시 row를 생성하는 코드는 어디에도 없다(`UserDocumentAccessCacheService.grantUserPermission()`/`bulkGrantUserPermission()`의 호출부는 `CollectionPermissionCommandService`/`DocumentPermissionCommandService` 두 곳뿐이고, 둘 다 `DIRECT_COLLECTION_PERMISSION`/`DIRECT_DOCUMENT_PERMISSION`만 넘긴다). `#21`의 `canReadDocument()` 1단계(OWNER)는 캐시를 거치지 않고 `document.getOwner()`를 직접 비교한다 — 즉 `AccessSourceType.OWNER`는 정의만 되어 있고 실제로는 죽은 값(dead value)이다. Javadoc이 실제 동작보다 앞서 있는 상태로 보이며, 코드 정리가 필요하다(아래 TODO 참고).

---

## 신규 파일

### 1. `domain/permission/entity/CollectionPermission.java`, `DocumentPermission.java`

```java
@Table(
    name = "collection_permissions",
    indexes = {
        @Index(columnList = "collection_id"),
        @Index(columnList = "target_type, user_id"),
        @Index(columnList = "target_type, department_id"),
        @Index(columnList = "target_type, role_id"),
        @Index(columnList = "expires_at")
    }
)
public class CollectionPermission extends BaseEntity {
    @ManyToOne @JoinColumn(name = "collection_id", nullable = false) private DocumentCollection collection;
    @Enumerated(EnumType.STRING) private PermissionTargetType targetType;
    @ManyToOne @JoinColumn(name = "user_id") private User user;             // targetType=USER일 때만
    @ManyToOne @JoinColumn(name = "department_id") private Department department; // targetType=DEPARTMENT일 때만
    @ManyToOne @JoinColumn(name = "role_id") private Role role;             // targetType=ROLE일 때만
    @Enumerated(EnumType.STRING) private PermissionType permissionType;
    private boolean canRead, canWrite, canAdmin;
    @ManyToOne @JoinColumn(name = "granted_by") private User grantedBy;
    private LocalDateTime grantedAt, expiresAt;
}
```
`DocumentPermission`도 `document_id` FK만 다르고 나머지 필드는 완전히 동일한 구조다.

**주의(엔티티 Javadoc에 이미 기록된 TODO)**: `targetType=USER`면 `user`만, `DEPARTMENT`면 `department`만, `ROLE`이면 `role`만 채워져야 한다는 규칙이 있는데, 이건 DB CHECK 제약이 아니라 애플리케이션 검증(`validateTargetType()`, 아래 참고)에만 의존한다. DB 마이그레이션에 CHECK 제약을 추가하는 게 TODO로 남아있다.

### 2. `domain/permission/entity/UserDocumentAccessCache.java`

```java
@Table(
    name = "user_document_access_cache",
    uniqueConstraints = { @UniqueConstraint(columnNames = {"user_id", "document_id", "source_type", "source_id"}) }
)
public class UserDocumentAccessCache extends BaseEntity {
    @ManyToOne @JoinColumn(name = "user_id", nullable = false) private User user;
    @ManyToOne @JoinColumn(name = "document_id", nullable = false) private Document document;
    private boolean canRead, canWrite, canAdmin;
    @Enumerated(EnumType.STRING) private AccessSourceType sourceType; // OWNER/DIRECT_DOCUMENT_PERMISSION/DIRECT_COLLECTION_PERMISSION
    private Long sourceId;          // 파생 근거 권한의 id, OWNER면 null
    private LocalDateTime computedAt, invalidatedAt, expiresAt;

    public void grant(boolean canRead, boolean canWrite, boolean canAdmin, LocalDateTime expiresAt) {
        this.canRead = canRead; this.canWrite = canWrite; this.canAdmin = canAdmin;
        this.invalidatedAt = null;
        this.computedAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    public void invalidate() {
        this.invalidatedAt = LocalDateTime.now();
    }
}
```
`(user_id, document_id, source_type, source_id)` 유니크 제약이 핵심이다 — 같은 사용자가 같은 문서에 대해 "OWNER로 1건", "직접 문서 권한으로 1건", "컬렉션 A 경유로 1건" 처럼 **출처별로 별도 캐시 row를 가질 수 있다**는 뜻이다. 그래서 권한 하나를 회수해도 다른 출처의 캐시는 그대로 남는다(`#21`의 5단계 판단, `#24`의 `sources` 배열이 여러 개 나올 수 있는 이유가 여기서 시작된다).

`grant()`/`invalidate()`를 엔티티 메서드로 둔 이유는 "캐시 row를 어떻게 갱신/무효화하는지"라는 도메인 규칙(무효화는 삭제가 아니라 `invalidatedAt` 설정)을 엔티티 안에 캡슐화하기 위해서다.

### 3. `domain/permission/enums/AccessSourceType.java`

```java
public enum AccessSourceType {
    OWNER,
    DIRECT_DOCUMENT_PERMISSION,
    DIRECT_COLLECTION_PERMISSION
}
```
`user_document_access_cache`에 저장되는 캐시가 "어디서 파생됐는지"를 나타낸다. `#24`의 `PermissionSourceType`(`OWNER`/`PUBLIC`/`USER_CACHE`/`ROLE`/`DEPARTMENT`)과 이름이 비슷하지만 **용도가 다른 별개의 enum**이다 — `AccessSourceType`은 캐시 테이블 내부 저장용, `PermissionSourceType`은 `/me` API 응답 노출용이다.

### 4. `domain/permission/service/command/CollectionPermissionCommandService.java`, `DocumentPermissionCommandService.java`

```java
public CollectionPermissionResponse grantPermission(Long collectionId, Long grantorId, GrantPermissionRequest request) {
    DocumentCollection collection = collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

    if (!permissionQueryService.canAdminCollection(grantorId, collection)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }

    validateTargetType(request);
    // targetType에 맞는 User/Role/Department 엔티티 조회 ...

    boolean[] permissions = resolvePermissions(request.permissionType());
    CollectionPermission permission = CollectionPermission.builder()...build();
    collectionPermissionRepository.save(permission);

    if (request.targetType() == PermissionTargetType.USER) {
        updateCacheForCollection(collectionId, targetUser, permissions, permission.getId(), request.expiresAt());
    }
    return permissionConverter.toCollectionPermissionResponse(permission);
}
```

**권한을 부여하려면 권한 부여자(`grantorId`)가 이미 `canAdminCollection`이어야 한다** — `#21`의 `PermissionQueryService`를 여기서도 재사용한다. `#16`의 `owner`만 되던 것과 달리, 이제 ADMIN 권한을 가진 사람(소유자 포함, ADMIN으로 위임받은 사람도 포함)이 다른 사람에게 권한을 나눠줄 수 있다.

```java
private void validateTargetType(GrantPermissionRequest request) {
    boolean valid = switch (request.targetType()) {
        case USER -> request.userId() != null && request.roleId() == null && request.departmentId() == null;
        case ROLE -> request.roleId() != null && request.userId() == null && request.departmentId() == null;
        case DEPARTMENT -> request.departmentId() != null && request.userId() == null && request.roleId() == null;
    };
    if (!valid) throw new DocGridException(ErrorCode.INVALID_TARGET_TYPE);
}
```
`targetType`에 안 맞는 ID 필드 조합(예: `USER`인데 `roleId`도 같이 옴)을 400으로 걸러낸다 — 위에서 언급한 "DB CHECK 제약 대신 애플리케이션 검증"이 바로 이 메서드다.

```java
// 컬렉션 권한(USER)을 부여하면 컬렉션 소속 문서 전체에 캐시를 일괄 갱신한다 (N+1 방지)
private void updateCacheForCollection(Long collectionId, User targetUser, boolean[] permissions,
                                      Long sourceId, LocalDateTime expiresAt) {
    List<Document> documents = collectionDocumentRepository.findAllByCollectionId(collectionId)
            .stream().map(CollectionDocument::getDocument).toList();
    cacheService.bulkGrantUserPermission(targetUser, documents,
            permissions[0], permissions[1], permissions[2],
            AccessSourceType.DIRECT_COLLECTION_PERMISSION, sourceId, expiresAt);
}
```
컬렉션에 문서가 100개면 캐시 row도 최대 100개가 갱신/생성될 수 있다 — 하나씩 INSERT하면 N+1이라 배치 처리(`bulkGrantUserPermission`, 아래 참고)로 넘긴다.

`DocumentPermissionCommandService`는 거의 동일한 구조이되, 캐시 갱신이 문서 1건뿐이라 `cacheService.grantUserPermission()`(단건) 하나만 호출한다.

### 5. `domain/permission/service/command/UserDocumentAccessCacheService.java`

```java
// USER 권한 캐시 저장 (있으면 갱신, 없으면 신규 insert), 문서 단건 권한 부여 시 호출
public void grantUserPermission(User user, Document document, boolean canRead, boolean canWrite, boolean canAdmin,
                                AccessSourceType sourceType, Long sourceId, LocalDateTime expiresAt) {
    cacheRepository.findByUserIdAndDocumentIdAndSourceTypeAndSourceId(user.getId(), document.getId(), sourceType, sourceId)
            .ifPresentOrElse(
                    cache -> cache.grant(canRead, canWrite, canAdmin, expiresAt),
                    () -> cacheRepository.save(UserDocumentAccessCache.builder()...build())
            );
}

// 컬렉션 권한(USER) 부여 시 소속 문서 전체 캐시를 배치로 갱신 (N+1 방지)
public void bulkGrantUserPermission(User user, List<Document> documents, boolean canRead, boolean canWrite,
                                    boolean canAdmin, AccessSourceType sourceType, Long sourceId, LocalDateTime expiresAt) {
    cacheRepository.bulkUpdateBySource(user.getId(), sourceType, sourceId, canRead, canWrite, canAdmin, expiresAt);

    Set<Long> cachedDocIds = Set.copyOf(
            cacheRepository.findDocumentIdsByUserIdAndSourceTypeAndSourceId(user.getId(), sourceType, sourceId));
    List<UserDocumentAccessCache> newCaches = documents.stream()
            .filter(d -> !cachedDocIds.contains(d.getId()))
            .map(d -> UserDocumentAccessCache.builder()...build())
            .toList();
    if (!newCaches.isEmpty()) cacheRepository.saveAll(newCaches);
}
```
`bulkGrantUserPermission()`이 두 단계로 나뉘는 이유: ① 이미 캐시가 있는 문서는 `UPDATE` 한 방(`bulkUpdateBySource`), ② 캐시가 아직 없는 문서(컬렉션에 새로 추가된 문서 등)만 골라서 `saveAll()` 배치 INSERT. "전부 delete 후 재삽입"하지 않고 갱신/신규를 나눈 이유는 UPDATE 한 번이 DELETE+INSERT 두 번보다 싸기 때문이다.

### 6. `domain/permission/repository/UserDocumentAccessCacheRepository.java` — 벌크 쿼리

```java
@Modifying(clearAutomatically = true)
@Query("""
        UPDATE UserDocumentAccessCache c
        SET c.canRead = :canRead, c.canWrite = :canWrite, c.canAdmin = :canAdmin,
            c.invalidatedAt = NULL, c.computedAt = CURRENT_TIMESTAMP, c.expiresAt = :expiresAt
        WHERE c.user.id = :userId AND c.sourceType = :sourceType AND c.sourceId = :sourceId
        """)
int bulkUpdateBySource(...);

@Modifying(clearAutomatically = true)
@Query("""
        UPDATE UserDocumentAccessCache c
        SET c.invalidatedAt = CURRENT_TIMESTAMP
        WHERE c.sourceType = :sourceType AND c.sourceId = :sourceId AND c.invalidatedAt IS NULL
        """)
int bulkInvalidateBySource(...);
```
`@Modifying` JPQL 벌크 쿼리를 쓴 이유: 엔티티를 하나씩 로드해서 `grant()`/`invalidate()` 호출 후 저장하면 캐시 row 수만큼 SELECT+UPDATE가 나간다. 벌크 UPDATE는 한 번의 SQL로 끝난다. `clearAutomatically = true`는 벌크 업데이트 후 영속성 컨텍스트에 남아있는 stale 엔티티를 지워서, 같은 트랜잭션 안에서 다시 조회할 때 DB 최신 값을 가져오게 한다.

### 7. `domain/permission/dto/request/GrantPermissionRequest.java`, 응답 DTO, `PermissionConverter`

```java
public record GrantPermissionRequest(
    @NotNull PermissionTargetType targetType,
    Long userId, Long roleId, Long departmentId,   // targetType에 맞는 것 하나만 채움
    @NotNull PermissionType permissionType,
    LocalDateTime expiresAt
) {}
```
`CollectionPermissionResponse`/`DocumentPermissionResponse`는 구조가 완전히 동일하다(대상이 컬렉션이냐 문서냐 차이만). `PermissionConverter`도 두 엔티티를 각각 대응하는 응답으로 변환하는 얇은 메서드 2개뿐이다.

### 8. `domain/permission/controller/PermissionController.java` — 이번 이슈 관련 4개 엔드포인트

```java
@PostMapping("/collections/{collectionId}")
public ResponseEntity<ApiResponse<CollectionPermissionResponse>> grantCollectionPermission(...) { ... }

@DeleteMapping("/collections/{collectionId}/{permissionId}")
public ResponseEntity<ApiResponse<Void>> revokeCollectionPermission(...) { ... }

@PostMapping("/documents/{documentId}")
public ResponseEntity<ApiResponse<DocumentPermissionResponse>> grantDocumentPermission(...) { ... }

@DeleteMapping("/documents/{documentId}/{permissionId}")
public ResponseEntity<ApiResponse<Void>> revokeDocumentPermission(...) { ... }
```
같은 컨트롤러에 `#24`의 `GET /documents/{documentId}/me`도 함께 있다.

---

## 로컬 검증 (Swagger 수동 테스트 — 실제 수행 기록)

`docs/test-results/kangcheolung-#21-permission-query-service.md`에서 발췌.

**USER 직접 권한 부여 → 캐시 즉시 생성 확인 (4.3절)**
```http
POST /permissions/documents/1
{ "targetType": "USER", "userId": 3, "permissionType": "READ", "expiresAt": null }
```
응답 `201`로 `permissionId=2` 확인 후 DB 직접 확인:
```text
document_permissions: id=2, user_id=3, document_id=1, can_read=true
user_document_access_cache: id=2, user_id=3, document_id=1, can_read=true, invalidated_at=NULL
```
권한 부여 직후 캐시가 **동기적으로 즉시** 생성됨을 DB에서 직접 확인했다(비동기 큐 등을 거치지 않음).

**컬렉션 ROLE 권한 부여 → 캐시(USER_CACHE)와 별개로 ROLE도 sources에 잡힘 (4.5절)**
```http
POST /permissions/collections/1
{ "targetType": "ROLE", "roleId": 4, "permissionType": "READ" }
```
이후 대상 사용자가 `/me` 호출 시 `sources: ["USER_CACHE", "ROLE"]` — 이전 절에서 생긴 USER 캐시와 이번 ROLE 권한이 **동시에** sources에 잡힌다(`#24`에서 자세히 다룸).

**USER 직접 권한 회수 → 캐시 무효화, ROLE 경로는 유지 (4.6절)**
```http
DELETE /permissions/documents/1/2
```
`204 No Content` 후 DB에서 `user_document_access_cache`의 해당 row가 `invalidated_at`에 시각이 찍혀 무효화됐음을 확인. `sources`는 `["ROLE"]`만 남아 ROLE 경로는 영향받지 않음을 확인.

### 자동 테스트

```bash
$ ./gradlew test --tests "*CollectionPermissionCommandServiceTest*" --tests "*DocumentPermissionCommandServiceTest*"
BUILD SUCCESSFUL
```
`CollectionPermissionCommandServiceTest` 8개, `DocumentPermissionCommandServiceTest` 8개, 총 16개 모두 통과(현재 기준 재검증).

---

## 에러 케이스 정리

| 상황 | HTTP | 코드 |
|---|---:|---|
| 컬렉션/문서 없음 | 404 | `COLLECTION-001` / `DOCUMENT-001` |
| 권한 레코드 없음(회수 시) | 404 | `PERMISSION-002` / `PERMISSION-003` |
| 대상 사용자/역할/부서 없음 | 404/400 | `USER-001` / `ROLE-001` / `DEPT-001` |
| `targetType`-ID 필드 조합 오류 | 400 | `PERMISSION-001` |
| 부여자가 ADMIN 권한 없음 | 403 | `ROLE-002`(`PERMISSION_DENIED`) |

---

## 설계 결정 요약

**USER만 캐시, ROLE/DEPARTMENT는 항상 live**: 역할/부서 구성원은 인사이동 등으로 자주 바뀌는데, 이걸 캐시하면 "구성원이 바뀌었는데 캐시가 안 바뀐" 정합성 문제가 생긴다. USER 직접 권한은 구성원 변경 문제가 없어서(대상이 특정 개인으로 고정) 캐시가 안전하다.

**캐시 무효화는 삭제가 아니라 `invalidated_at` 설정(soft invalidation)**: 레코드를 지우지 않고 무효화 시각만 찍는다. 언제 어떤 권한이 회수됐는지 이력이 DB에 남아 나중에 감사(audit)나 디버깅에 쓸 수 있다.

**컬렉션 권한 부여/회수 시 캐시를 배치(N+1 방지)로 처리**: 컬렉션에 문서가 많으면 개별 처리 시 N+1이 발생한다. 갱신 대상과 신규 대상을 나눠(`bulkUpdateBySource` + 필터링 후 `saveAll`) 최소 쿼리로 처리한다.

**권한 부여 자체에도 ADMIN 권한이 필요**: `#16`까지는 소유자만 문서를 추가할 수 있었는데, 이 이슈부터 "소유자가 다른 사람에게 ADMIN 권한을 위임하면, 그 사람도 권한을 나눠줄 수 있다"는 위임 구조가 생긴다. `canAdminCollection()`이 소유자와 ADMIN 위임자를 모두 포함해서 판단하므로 이 위임이 자연스럽게 성립한다.

**(추가) `canAdminCollection`을 ID 버전 + 엔티티 버전으로 분리**: `grantPermission()`이 컬렉션을 조회한 뒤 `canAdminCollection(grantorId, collectionId)`을 ID로 다시 호출하면 같은 row를 두 번 SELECT하게 되고, soft-delete된 컬렉션에도 새 권한을 부여할 수 있는 구멍이 있었다. `canAdminCollection(userId, DocumentCollection)` 엔티티 오버로드를 추가해 이미 조회한 엔티티를 그대로 넘기도록 하고, ID 버전에는 `status != DELETED` 필터를 넣었다(`#16` 문서의 동일 리팩토링과 같은 패턴).

---

## 남은 이슈 / TODO

- ~~`target_type`별 단일 FK 제약이 DB 레벨(CHECK)이 아니라 애플리케이션 검증에만 있다(엔티티 Javadoc에 이미 기록됨).~~ → 확인 결과 이미 해결되어 있음: `V11__create_collection_permissions.sql`/`V12__create_document_permissions.sql`에 `CHECK` 제약이 반영되어 있다(엔티티 Javadoc만 갱신되지 않은 상태였음, `#16` 문서에서도 동일하게 확인).
- `grantPermission()`/`canAdminCollection()`이 컬렉션을 두 번 조회하던 중복 쿼리 및 soft-delete된 컬렉션에도 권한을 부여할 수 있던 문제 → 해결됨(아래 "설계 결정 요약" 참고). `revokePermission()`은 자체 `findById` 호출이 없어 `canAdminCollection(revokerId, collectionId)`(ID 버전) 내부의 status 필터를 그대로 적용받는다 — 별도 수정 불필요.
- `expiresAt`이 지난 권한을 정리(삭제 또는 자동 무효화)하는 배치가 없다 — live 조회 시 `expiresAt > CURRENT_TIMESTAMP` 조건으로 걸러지긴 하지만, 만료된 레코드 자체는 DB에 계속 쌓인다.
- `AccessSourceType.OWNER`가 정의만 되어 있고 실제로 생성되지 않는다(위 "확인된 불일치" 참고) — enum에서 제거하거나, 실제로 OWNER 캐시를 생성하도록 코드를 맞추거나 둘 중 하나로 정리가 필요하다.
- ~~`PermissionController`의 컬렉션/문서 권한 부여·회수 4개 엔드포인트 Swagger description이 "소유자(owner)만 가능"이라고 적혀 있던 문제~~ → 코드리뷰로 발견해 실제 인가 규칙(`canAdminCollection()`/`canAdminDocument()`, ADMIN 위임자도 허용)에 맞게 4곳 모두 "ADMIN 권한 보유자(소유자 포함)"로 수정 완료.

## 다음 단계

`#21`(`PermissionQueryService` — 이 이슈에서 만든 권한 데이터를 실제로 판단하는 서비스), `#24`(문서 권한 확인 API), `#29`(컬렉션 관리 API)로 이어진다.
