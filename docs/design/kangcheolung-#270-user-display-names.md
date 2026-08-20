# #270 문서·컬렉션·권한 화면에 사용자 ID만 표시되던 문제 수정

closes #270

---

## 배경

QA 과정에서 문서 목록, 컬렉션 목록/상세, 컬렉션 문서 목록, 권한 관리 4개 화면 모두에서 소유자·추가자·권한 대상·권한 부여자가 이름 없이 `user #4`, `owner #4`, `USER #2` 처럼 ID로만 표시되는 문제가 발견됐다. 목록 API 응답에 사용자 표시명 필드 자체가 없어서 프런트가 ID를 그대로 찍는 구조였다.

문서 **상세**(`DocumentDetailResponse.ownerName`)에는 이미 이름이 내려가고 있었는데, 그건 단건 조회라 `document.getOwner().getName()` 한 번만 lazy-load하면 되니 쌌던 것뿐이고, 목록 4곳은 "여러 사용자 ID를 한 번에 처리"해야 해서 그대로 옮기면 N+1이 난다 — 그래서 그동안 미뤄져 있었다.

## 설계 결정

- **표시 포맷은 기존 문서 상세와 동일하게 `이름 (#ID)`.** 이 화면들은 관리자용 내부 도구라 감사·트러블슈팅 때 ID로 DB/API 조회할 일이 많아, 이름만 보여주고 ID를 숨기는 것보다 정확성을 우선했다. 새 포맷을 만들지 않고 기존 선례를 그대로 따랐다.
- **쿼리를 실제로 바꾼 곳은 2곳뿐.** 나머지는 이미 `JOIN FETCH`가 돼 있어 DTO 필드 추가 + 컨버터에서 `.getName()` 한 줄씩 꺼내기만 하면 됐다.
  - `DocumentRepository.findAllByIdIn` (문서 목록) — 기존에 `LEFT JOIN FETCH d.currentVersion`만 있고 `owner`는 없어서 `JOIN FETCH d.owner` 추가. 이 컨버터(`DocumentSummaryConverter`)는 컬렉션 문서 목록에서도 재사용되는데, 그쪽 쿼리(`CollectionDocumentRepository.findReadableDocuments`)는 이미 `d.owner`를 fetch join하고 있어서 별도 조치 불필요.
  - `CollectionRepository.findReadableCollections` (컬렉션 목록, 네이티브 SQL) — `users` 테이블 JOIN + `u.name AS owner_name` 컬럼 추가. 재귀 CTE(권한 판단 로직)는 손대지 않고 최종 SELECT에만 붙였다.
  - `CollectionDocumentRepository.findReadableDocuments`, `DocumentPermissionRepository.findAllWithTargetsByDocumentId`, `CollectionPermissionRepository.findAllWithTargetsByCollectionId`는 이미 필요한 관계를 전부 `JOIN FETCH`하고 있어 쿼리 변경 없음.
- **`CollectionConverter.toResponse(DocumentCollection)`을 오버로드로 분리.** 이 메서드는 `getCollection()`(단건 상세)과 `getChildren()`(하위 컬렉션 여러 건) 양쪽에서 쓰인다. `owner`는 LAZY라 이름까지 무조건 꺼내면 `getChildren()` 쪽에서 N+1이 터진다(프런트가 하위 컬렉션 카드엔 소유자를 표시하지도 않는데 말이다). 그래서 이름이 필요한 호출자만 `toResponse(collection, ownerName)`으로 이름을 명시적으로 넘기게 시그니처를 쪼갰다. 기존 1-인자 버전은 `ownerName = null`로 위임하는 얇은 래퍼로 남겨 `getChildren()` 쪽은 코드 변경 없이 그대로 동작한다.
- **프런트 `PermissionsPage.tsx`의 `targetLabel()` 단순화.** 지난 QA-P1-05 때 ROLE/DEPARTMENT 이름을 프런트에서 이미 로드된 `roles`/`departments` 배열로 직접 찾아 보여주던 임시 로직이 있었는데, 이제 백엔드가 `roleName`/`departmentName`을 직접 내려주므로 그 lookup을 제거하고 백엔드 값을 그대로 쓰도록 정리했다. USER도 이제 `userName`이 내려와서 세 타입 모두 통일된 방식으로 처리한다.

## API 명세 변경

### `GET /api/documents`, `GET /collections/{collectionId}/documents`

응답의 `document` 객체(`DocumentSummaryResponse`)에 필드 추가:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `ownerName` | string | 소유자 이름 |

### `GET /collections`, `GET /collections/{collectionId}`, `POST /collections`

`CollectionResponse`에 필드 추가:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `ownerName` | string \| null | 소유자 이름. `GET /collections/{id}/children`(하위 컬렉션 목록)에서는 N+1 방지를 위해 항상 `null` |

### `GET /collections/{collectionId}/documents`

`CollectionDocumentListItemResponse`에 필드 추가:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `addedByName` | string \| null | 문서를 컬렉션에 추가한 사용자 이름. `addedBy`가 `null`이면 함께 `null` |

### `GET /permissions/documents/{documentId}`, `GET /permissions/collections/{collectionId}`

`DocumentPermissionResponse`/`CollectionPermissionResponse`에 필드 추가:

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `userName` | string \| null | 대상이 USER일 때 사용자 이름 |
| `roleName` | string \| null | 대상이 ROLE일 때 역할 이름 |
| `departmentName` | string \| null | 대상이 DEPARTMENT일 때 부서 이름 |
| `grantedByName` | string \| null | 권한을 부여한 사용자 이름 |

**에러 케이스**: 이번 변경은 조회 결과에 필드만 추가하는 것이라 기존 에러 케이스(403/404 등)에 변동 없음.

## 변경 파일

**백엔드**
- `DocumentRepository.java` — `findAllByIdIn`에 `JOIN FETCH d.owner` 추가
- `DocumentSummaryResponse.java`, `DocumentSummaryConverter.java` — `ownerName` 추가
- `CollectionRepository.java` — `findReadableCollections` 네이티브 쿼리에 `users` JOIN + `owner_name` 컬럼 추가
- `CollectionRow.java`, `CollectionResponse.java` — `ownerName`(`getOwnerName()`) 추가
- `CollectionConverter.java` — `toResponse(DocumentCollection)` 오버로드 분리, `toDocumentListItemResponse()`에 `addedByName` 추가
- `CollectionCommandService.java`, `CollectionQueryService.java` — `toResponse()` 호출부에 owner 이름 전달
- `CollectionDocumentListItemResponse.java` — `addedByName` 추가
- `DocumentPermissionResponse.java`, `CollectionPermissionResponse.java`, `PermissionConverter.java` — `userName`/`roleName`/`departmentName`/`grantedByName` 추가

**프런트**
- `api-types.ts` — `DocumentSummary.ownerName`, `Collection.ownerName`, `CollectionDocument.addedByName`, `PermissionGrant.userName/roleName/departmentName/grantedByName` 추가
- `DocumentsPage.tsx` — 목록 소유자 표시
- `CollectionsPage.tsx` — 카드/상세 owner, 컬렉션 문서 목록 추가자 표시
- `PermissionsPage.tsx` — `targetLabel()`이 백엔드 이름 필드를 직접 사용하도록 단순화, 부여자 표시

## 테스트

- 백엔드: 관련 컨트롤러/서비스 단위 테스트(`CollectionCommandServiceTest`, `CollectionQueryServiceTest`, `CollectionControllerTest`, `DocumentQueryServiceTest`, `PermissionControllerTest` 등) 통과 — 새 record 필드 추가로 깨진 테스트 fixture(`CollectionFixture`, 컨트롤러 테스트의 `new CollectionResponse(...)` 등)와 `collectionConverter.toResponse(...)` mock 스텁을 오버로드에 맞게 갱신
- `./backend/gradlew -p backend build` 전체 통과 — 무관한 기존 flaky 통합 테스트 2건(`DocumentIndexingFailureIntegrationTest`, `RagJobWorkerConcurrentQueueIntegrationTest`, PostgreSQL/동시성 타이밍 이슈)만 실패, 이번 변경과 무관
- 프런트: `eslint`, `tsc --noEmit`, `npm run test`(20개) 전부 통과
- 사용자가 로컬 풀스택(백엔드+프런트)에서 문서 목록·컬렉션 목록/상세·컬렉션 문서 목록·권한 관리 4개 화면 전부 직접 확인 완료 (예: 권한 관리 화면에서 `USER QA테스트`, 부여자 `강철웅`으로 정상 표시)
