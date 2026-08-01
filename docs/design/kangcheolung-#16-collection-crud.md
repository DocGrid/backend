# #16 컬렉션 기본 CRUD — 생성/조회/문서 추가

closes #16

---

## 배경

문서를 하나하나 권한 관리하면 관리 비용이 너무 커진다. 그래서 문서를 그룹으로 묶는 **컬렉션(폴더/워크스페이스)** 단위를 도입하고, 권한도 원칙적으로 컬렉션 단위(`collection_permissions`)로 부여한다. 문서 단위 예외 권한(`document_permissions`)은 최소한으로만 쓴다(`#18` 참고).

이번 이슈는 컬렉션의 가장 기본적인 CRUD 3개를 만든다: 생성, 단건 조회, 컬렉션에 문서 추가. 목록 조회/삭제/문서 제거는 `#29`에서 이어서 만든다.

**클래스명이 `DocumentCollection`인 이유**: `java.util.Collection`과 이름이 충돌해서 그대로 `Collection`을 쓸 수 없다. 테이블명은 `collections`이지만 엔티티 클래스명만 `DocumentCollection`으로 바꿨다.

---

## 전체 흐름

```text
POST /collections (컬렉션 생성)
    │
    ▼
CollectionController.createCollection()
    │
    ▼
CollectionCommandService.createCollection()
    ├─ parentCollectionId 있으면 상위 컬렉션 존재 확인
    ├─ visibility 미입력 시 PRIVATE 기본값 적용
    └─ DocumentCollection 저장 (status=ACTIVE, owner=요청자)
    │
    ▼
201 Created + CollectionResponse

POST /collections/{collectionId}/documents (문서 추가)
    │
    ▼
CollectionCommandService.addDocument()
    ├─ 컬렉션 존재 확인
    ├─ permissionQueryService.canWriteCollection() 확인  ← #21에서 만든 서비스 재사용
    ├─ 문서 존재 확인
    ├─ 중복 추가 확인 → 있으면 409
    └─ CollectionDocument 저장
    │
    ▼
201 Created + CollectionDocumentResponse
```

---

## 신규 파일

### 1. `domain/collection/entity/DocumentCollection.java`

```java
@Getter
@Entity
@Table(name = "collections", indexes = { ... })
public class DocumentCollection extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    // 상위 컬렉션 self-FK, 최상위 컬렉션은 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_collection_id")
    private DocumentCollection parentCollection;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    private VisibilityType visibility;

    @Enumerated(EnumType.STRING)
    private CollectionStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public DocumentCollection(User owner, DocumentCollection parentCollection, String name, String description,
                               VisibilityType visibility, CollectionStatus status) {
        ...
        this.status = status != null ? status : CollectionStatus.ACTIVE;
    }

    public void markDeleted(LocalDateTime deletedAt) {
        this.status = CollectionStatus.DELETED;
        this.deletedAt = deletedAt;
    }
}
```

- `visibility`는 `document` 도메인의 `VisibilityType`(`PRIVATE`/`COLLECTION`/`DEPARTMENT`/`PUBLIC`)을 그대로 재사용한다 — 문서와 컬렉션이 같은 공개범위 개념을 공유하므로 별도 enum을 새로 만들지 않았다.
- `parentCollection`이 self-FK라 컬렉션 트리(폴더 계층) 구조를 표현할 수 있지만, 이번 이슈에서는 "생성 시 상위 컬렉션 존재 확인" 정도만 쓰고 트리 순회 API는 만들지 않았다.
- 삭제는 `markDeleted()`로 `status`/`deletedAt`만 바꾸는 soft delete다 (`#29`에서 실제로 호출).

### 2. `domain/collection/entity/CollectionDocument.java`

```java
@Table(
    name = "collection_documents",
    uniqueConstraints = { @UniqueConstraint(columnNames = {"collection_id", "document_id"}) }
)
public class CollectionDocument extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "collection_id", nullable = false)
    private DocumentCollection collection;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "document_id", nullable = false)
    private Document document;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "added_by")
    private User addedBy;
    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;
}
```

컬렉션-문서 N:M을 해소하는 중간 엔티티. `(collection_id, document_id)` 유니크 제약이 DB 레벨의 최종 중복 방어선이고, 애플리케이션에서는 `existsByCollectionIdAndDocumentId()`로 먼저 걸러 409를 반환한다.

### 3. `domain/collection/enums/CollectionStatus.java`

```java
public enum CollectionStatus {
    ACTIVE,
    ARCHIVED,
    DELETED
}
```
`ACTIVE`/`DELETED` 두 개만 실제로 코드에서 분기 처리된다(`markDeleted()`, `findAllByOwnerIdAndStatus(..., ACTIVE)`). `ARCHIVED`는 enum 값만 정의되어 있고 이번 이슈 범위에서는 전환 로직이 없다 — 향후 "보관함" 기능 확장을 대비해 미리 값만 잡아둔 것으로 보인다.

### 4. `domain/collection/service/command/CollectionCommandService.java` — `createCollection`, `addDocument`

```java
public CollectionResponse createCollection(Long userId, CreateCollectionRequest request) {
    User owner = userRepository.getReferenceById(userId);

    DocumentCollection parentCollection = null;
    if (request.parentCollectionId() != null) {
        parentCollection = collectionRepository.findById(request.parentCollectionId())
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
    }

    VisibilityType visibility = request.visibility() != null ? request.visibility() : VisibilityType.PRIVATE;

    DocumentCollection collection = DocumentCollection.builder()
            .owner(owner).parentCollection(parentCollection).name(request.name())
            .description(request.description()).visibility(visibility).status(CollectionStatus.ACTIVE)
            .build();

    collectionRepository.save(collection);
    return collectionConverter.toResponse(collection);
}

public CollectionDocumentResponse addDocument(Long collectionId, Long userId, AddDocumentRequest request) {
    DocumentCollection collection = collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

    if (!permissionQueryService.canWriteCollection(userId, collection)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }

    Document document = documentRepository.findById(request.documentId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

    if (collectionDocumentRepository.existsByCollectionIdAndDocumentId(collectionId, request.documentId())) {
        throw new DocGridException(ErrorCode.COLLECTION_DOCUMENT_ALREADY_EXISTS);
    }

    User addedBy = userRepository.getReferenceById(userId);
    CollectionDocument collectionDocument = CollectionDocument.builder()
            .collection(collection).document(document).addedBy(addedBy).addedAt(LocalDateTime.now())
            .build();

    collectionDocumentRepository.save(collectionDocument);
    return collectionConverter.toDocumentResponse(collectionDocument);
}
```

- `userRepository.getReferenceById(userId)`: `JpaRepository`가 기본 제공하는 프록시 조회 메서드. `SearchResultCommandService`(RAG 블록) 등이 쓰는 `entityManager.getReference()`와 동일한 목적(불필요한 SELECT 생략)을, Spring Data가 표준으로 제공하는 방식으로 구현한 것이다.
- `addDocument()`는 `#21`에서 만든 `PermissionQueryService.canWriteCollection()`을 그대로 재사용한다 — 이 이슈에서 권한 판단 로직을 새로 만들지 않는다.
- 존재 확인(컬렉션) → 권한 확인 → 존재 확인(문서) → 중복 확인 순서다. 컬렉션이 없는데 권한부터 확인하면 404 대신 엉뚱한 에러가 날 수 있어 존재 확인이 항상 먼저 온다.
- ~~**확인된 갭**: `collectionRepository.findById(collectionId)`는 `status`를 전혀 필터링하지 않는다(`CollectionRepository`에는 `findAllByOwnerIdAndStatus`만 있고, ID 단건 조회에 상태 조건을 건 메서드가 없다). 즉 `status=DELETED`(소프트 삭제된) 컬렉션에도 `addDocument()`로 문서를 계속 추가할 수 있다 — 아래 "남은 이슈/TODO"에 기록.~~ → 해결됨: `findById(...).filter(c -> c.getStatus() != CollectionStatus.DELETED)`로 soft-delete된 컬렉션을 걸러내도록 수정.

### 5. `domain/collection/service/query/CollectionQueryService.java` — `getCollection`

```java
public CollectionResponse getCollection(Long userId, Long collectionId) {
    DocumentCollection collection = collectionRepository.findById(collectionId)
            .filter(c -> c.getStatus() != CollectionStatus.DELETED)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
    if (!permissionQueryService.canReadCollection(userId, collection)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }
    return collectionConverter.toResponse(collection);
}
```

~~**주의**: 컬렉션 단건 조회는 권한 체크를 하지 않는다. `visibility`/`status`와 무관하게 ID만 알면 누구나(인증된 사용자면) 조회 가능하다. 이건 명시적으로 의도된 설계인지, 이번 이슈 범위에서 권한 체크가 빠진 것인지 코드만으로는 확정하기 어렵다 — 컬렉션 메타데이터(이름/설명 정도)만 노출되고 소속 문서 내용은 노출되지 않아서 위험도가 낮다고 판단했을 가능성이 있다.~~ → 해결됨: `PermissionQueryService.canReadCollection()`(소유자/PUBLIC/USER·ROLE·DEPARTMENT 권한)을 추가해서 `getCollection()`에 명시적으로 연결했다. `#21` 문서에 `canReadCollection` 메서드 설명 추가 필요.

### 6. `domain/collection/repository/CollectionRepository.java`, `CollectionDocumentRepository.java`

```java
public interface CollectionRepository extends JpaRepository<DocumentCollection, Long> {
    List<DocumentCollection> findAllByOwnerIdAndStatus(Long ownerId, CollectionStatus status); // #29에서 사용
}

public interface CollectionDocumentRepository extends JpaRepository<CollectionDocument, Long> {
    boolean existsByCollectionIdAndDocumentId(Long collectionId, Long documentId);
    List<CollectionDocument> findAllByCollectionId(Long collectionId); // #18에서 사용
    Optional<CollectionDocument> findByCollectionIdAndDocumentId(Long collectionId, Long documentId); // #29에서 사용
}
```
둘 다 이번 이슈에서 실제로 쓰는 메서드보다 많은 메서드를 갖고 있다 — `#18`, `#29`가 같은 리포지토리를 이어서 쓰기 때문에 미리 같이 정의된 것으로 보인다.

### 7. DTO / Converter

```java
public record CreateCollectionRequest(@NotBlank String name, String description, Long parentCollectionId, VisibilityType visibility) {}
public record AddDocumentRequest(@NotNull Long documentId) {}
public record CollectionResponse(Long collectionId, String name, String description, Long ownerUserId,
                                  Long parentCollectionId, VisibilityType visibility, CollectionStatus status, LocalDateTime createdAt) {}
public record CollectionDocumentResponse(Long collectionId, Long documentId, Long addedBy, LocalDateTime addedAt) {}
```
`CollectionConverter`는 엔티티 → 응답 DTO 변환만 담당하는 얇은 `@Component`다(다른 도메인의 Converter 패턴과 동일).

### 8. `domain/collection/controller/CollectionController.java` — 이번 이슈 관련 3개 엔드포인트

```java
@PostMapping
public ResponseEntity<ApiResponse<CollectionResponse>> createCollection(...) { ... }

@GetMapping("/{collectionId}")
public ResponseEntity<ApiResponse<CollectionResponse>> getCollection(@PathVariable Long collectionId) { ... }

@PostMapping("/{collectionId}/documents")
public ResponseEntity<ApiResponse<CollectionDocumentResponse>> addDocument(...) { ... }
```
같은 컨트롤러 클래스에 `#29`의 `GET /collections`, `DELETE /{collectionId}`, `DELETE /{collectionId}/documents/{documentId}`도 함께 있다 — 컬렉션 리소스를 다루는 엔드포인트는 전부 `CollectionController` 하나에 모여 있다.

---

## 로컬 검증 (Swagger 수동 테스트 — 실제 수행 기록)

`docs/test-results/kangcheolung-#21-permission-query-service.md`에 `#16`~`#29` 통합 Swagger 테스트 기록이 있다. 이번 이슈와 직접 관련된 부분만 발췌한다.

**컬렉션 중복 문서 추가 방어 (4.4절)**
```http
POST /collections/1/documents
{ "documentId": 1 }
```
```json
{
  "status": 409,
  "code": "COLLECTION-002",
  "message": "이미 컬렉션에 추가된 문서입니다.",
  ...
}
```
이미 추가된 문서를 다시 추가하면 409가 정확히 반환됨을 확인.

**컬렉션에 문서 추가 정상 케이스 (4.8절)**
```http
POST /collections/1/documents
```
```json
{
  "status": 201,
  "data": { "collectionId": 1, "documentId": 1, "addedBy": 2, "addedAt": "2026-07-17T17:24:25.353756" }
}
```

### 자동 테스트

```bash
$ ./gradlew test --tests "*CollectionCommandServiceTest*" --tests "*CollectionQueryServiceTest*"
BUILD SUCCESSFUL
```
`CollectionCommandServiceTest` 15개, `CollectionQueryServiceTest` 4개(`canReadCollection` 도입으로 권한없음/삭제된 컬렉션 케이스 추가), 총 19개 모두 통과(현재 기준 재검증).

---

## 에러 케이스 정리

| 상황 | HTTP | 코드 |
|---|---:|---|
| 상위 컬렉션 없음(`parentCollectionId` 지정 시) | 404 | `COLLECTION-001` |
| 컬렉션 없음(`addDocument`) | 404 | `COLLECTION-001` |
| 문서 없음 | 404 | `DOCUMENT-001` |
| 컬렉션 쓰기 권한 없음(`canWriteCollection=false`) | 403 | `ROLE-002`(`PERMISSION_DENIED`) |
| 이미 추가된 문서 | 409 | `COLLECTION-002` |

---

## 설계 결정 요약

**기본 권한 단위는 컬렉션, 문서 단위는 예외로만**: 문서 하나하나에 권한을 주면 관리 비용이 사용자 수 × 문서 수로 커진다. 컬렉션 단위로 묶어서 권한을 부여하면 "컬렉션에 권한 1번 부여 → 소속 문서 전체에 적용"이 되므로 관리 포인트가 줄어든다. `document_permissions`(`#18`)은 이 기본 원칙의 예외 통로로만 남겨뒀다.

**`visibility`를 문서 도메인과 공유**: 컬렉션도 문서와 동일한 4단계 공개범위(`PRIVATE`/`COLLECTION`/`DEPARTMENT`/`PUBLIC`) 개념이 필요해서, 별도 enum을 만들지 않고 `document.enums.VisibilityType`을 그대로 재사용했다.

**컬렉션 트리(`parentCollection`)는 자기참조 FK만 준비하고 순회 API는 만들지 않음**: 나중에 "하위 컬렉션 전체 조회" 같은 기능이 필요해질 걸 대비해 스키마는 미리 잡아뒀지만, 지금 당장 필요하지 않은 API까지 만들지 않았다(Simplicity First).

**(추가) `canReadCollection` 도입 + soft-delete 필터를 "엔티티 오버로드"로 통일**: `getCollection()`/`addDocument()`가 컬렉션을 조회한 뒤 `PermissionQueryService`를 ID로 다시 호출하면 같은 row를 두 번 SELECT하게 된다. `PermissionQueryService.canReadCollection`/`canWriteCollection`/`canAdminCollection`을 각각 "ID 버전(조회 후 위임) + 엔티티 버전(조회 없이 판단)"으로 나눠서, 이미 엔티티를 들고 있는 호출부는 엔티티 버전을 호출해 중복 조회를 없앴다. soft-delete 필터(`status != DELETED`)는 `AuthCommandService.signup()`의 부서 활성 필터와 동일하게 `findById(...).filter(...).orElseThrow(...)` 관용구로 통일했다.

---

## 남은 이슈 / TODO

- ~~`getCollection()`(단건 조회)에 권한 체크가 없다~~ → 해결됨: `canReadCollection()` 추가 (아래 "설계 결정 요약" 참고).
- ~~`getCollection()`과 `addDocument()` 둘 다 `collectionRepository.findById()`만 쓰고 `status`를 확인하지 않는다~~ → 해결됨: `.filter(c -> c.getStatus() != CollectionStatus.DELETED)`를 두 메서드 모두에 추가. `#29`의 `deleteCollection()`/`removeDocument()`에도 동일하게 적용됨(해당 문서 참고).
- `CollectionStatus.ARCHIVED`는 정의만 되어 있고 전환 로직이 없다.
- ~~`CollectionPermission`/`DocumentPermission` 엔티티의 Javadoc에 이미 명시된 TODO: `target_type`별로 단일 FK만 채워져야 한다는 규칙이 DB CHECK 제약으로 강제되지 않고 애플리케이션 검증(`validateTargetType()`, `#18`)에만 의존한다.~~ → 확인 결과 이미 해결되어 있음: `V11__create_collection_permissions.sql`/`V12__create_document_permissions.sql`에 `CHECK` 제약이 반영되어 있다(엔티티 Javadoc만 갱신되지 않은 상태였음).

## 다음 단계

`#18`(권한 부여/회수), `#21`(PermissionQueryService), `#24`(문서 권한 확인 API), `#29`(컬렉션 관리 API — 목록/삭제/문서 제거)로 이어진다.
