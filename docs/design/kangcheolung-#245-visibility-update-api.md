# #245 문서/컬렉션 공개 범위(PRIVATE/PUBLIC) 수정 API 추가

closes #245

---

## 배경

문서와 컬렉션 모두 생성 시점에만 `visibility`(PRIVATE/PUBLIC)를 지정할 수 있고, 생성 후에는
바꿀 방법이 없었다. 실수로 비공개로 올렸거나 반대로 전체공개를 다시 잠그고 싶은 경우를 위해
PATCH 엔드포인트를 추가한다.

`VisibilityType`에는 `COLLECTION`/`DEPARTMENT` 값도 있지만, 실제 접근판정 코드
(`PermissionQueryService.canReadDocument`/`canReadCollection`)에서 이 두 값은 전혀 읽지
않는 미구현 값이다. 이 상태에서 수정 API가 두 값을 그대로 받아주면 사용자가 "부서 공개로
바꿨다"고 착각해도 실제 접근권은 전혀 바뀌지 않는, 착각을 유발하는 상황이 된다. 그래서 이번
API는 `PRIVATE`/`PUBLIC` 토글만 지원하고 나머지 두 값은 명시적으로 거부한다.
`VisibilityType` enum 정리(두 값을 없앨지, 구현할지) 자체는 이번 스코프가 아니다.

## 권한 기준

문서/컬렉션 모두 **소유자만** 변경할 수 있다. ADMIN 권한을 위임받은 사용자는 제외한다.

- 문서: `document.getOwner().getId().equals(userId)`
- 컬렉션: `collection.getOwner().getId().equals(userId)`

**결정 이유**: 삭제(`deleteDocument`)나 권한관리(`getCollectionPermissions`)는 ADMIN 위임자도
포함하는 기준이라 처음엔 그와 맞추려 했다. 하지만 visibility를 PUBLIC으로 바꾸는 건 "이
문서/컬렉션을 조직 전체에 노출시킬지"를 정하는 결정이고, 위임받은 관리자가 소유자 의사와
무관하게 그 결정을 내릴 수 있게 하는 건 과도하다고 판단해 컬렉션 삭제(`deleteCollection`)/
문서 제거(`removeDocument`)와 같은 소유자 전용 기준으로 좁혔다.

## API 명세

### `PATCH /api/documents/{documentId}/visibility`

**Request**
```json
{ "visibility": "PUBLIC" }
```
- `visibility`: `NotNull`, `PRIVATE` 또는 `PUBLIC`만 허용

**Response**: `204 No Content`

**에러 케이스**

| 상황 | ErrorCode | HTTP |
|---|---|---|
| 대상 문서 없음(또는 이미 삭제됨) | `DOCUMENT_NOT_FOUND` | 404 |
| 소유자가 아님 | `PERMISSION_DENIED` | 403 |
| `visibility`가 `COLLECTION`/`DEPARTMENT` | `DOCUMENT_VISIBILITY_NOT_SUPPORTED` | 400 |

### `PATCH /collections/{collectionId}/visibility`

**Request**
```json
{ "visibility": "PUBLIC" }
```

**Response**: `204 No Content`

**에러 케이스**

| 상황 | ErrorCode | HTTP |
|---|---|---|
| 대상 컬렉션 없음(또는 이미 삭제됨) | `COLLECTION_NOT_FOUND` | 404 |
| 소유자가 아님 | `PERMISSION_DENIED` | 403 |
| `visibility`가 `COLLECTION`/`DEPARTMENT` | `COLLECTION_VISIBILITY_NOT_SUPPORTED` | 400 |

## 구현

- `Document`/`DocumentCollection` entity에 `updateVisibility(VisibilityType)` 추가 (단순 필드 대입)
- `DocumentCommandService.updateVisibility` — 기존 `updateMetadata`/`deleteDocument`와 동일하게
  `findActiveDocumentForUpdate`(비관적 락)로 조회 후 소유자 확인 → 값 검증 → 변경
- `CollectionCommandService.updateVisibility` — 기존 `deleteCollection` 등과 동일한 조회
  패턴(`findById` + 상태 필터) 사용, 소유자 확인 → 값 검증 → 변경
- 검색 접근판정(`AccessibleDocumentQueryService`)은 `visibility = 'PUBLIC'`을 매 요청마다
  라이브 쿼리로 확인하는 구조라, 토글 후 별도 캐시 무효화나 재인덱싱이 필요 없다
  (`user_document_access_cache`는 권한부여(permission grant/revoke) 쪽에서만 쓰는 캐시이고
  visibility와는 무관하다).

## 로컬 검증

- `./backend/gradlew -p backend compileJava` 통과
- `DocumentCommandServiceTest`, `CollectionCommandServiceTest` 신규 케이스(정상/권한없음/
  잘못된 값/대상없음) 포함 전체 통과
- `./backend/gradlew -p backend build -x test` 통과
