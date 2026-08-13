# 문서 목록 조회 API

closes #153

## 배경

문서 도메인에는 업로드(`POST /api/documents`)와 단건 인덱싱 상태 조회
(`GET /api/documents/{documentId}/status`)만 있고, **사용자가 읽을 수 있는 문서 목록을
반환하는 API가 없었다.** 문서를 한 번 업로드하고 나면 그 `documentId`를 따로 기억하지 않는 한
다시 찾아갈 방법이 없어, 문서 화면을 구성할 수 없는 상태였다.

목록에 필요한 권한 판정은 이미 존재한다. 검색 pre-filter가 쓰는
`DocumentRepository.findReadableDocumentIds`가 5가지 접근 경로(OWNER / PUBLIC /
USER 캐시 / ROLE live / DEPARTMENT live)를 UNION으로 판정한다. 다만 이 쿼리는
`d.status = 'INDEXED'`가 하드코딩되어 있어 그대로는 목록에 쓸 수 없다. 목록에서는 인덱싱 중
(`INDEXING`)이거나 실패(`FAILED`)한 문서도 보여야 사용자가 진행 상황을 확인할 수 있기
때문이다.

## 설계 판단

### 쿼리를 복제하지 않고 상태 조건만 파라미터화했다

UNION 7개 브랜치짜리 네이티브 쿼리를 목록용으로 복사하면 권한 정책이 두 벌이 되어, 이후 접근
경로가 추가될 때 한쪽만 고치는 사고가 나기 쉽다. `d.status = 'INDEXED'`를
`d.status IN (:statuses)`로 바꾸고, 검색은 호출부에서 `INDEXED`만 넘겨 기존 동작을 그대로
유지한다.

| 호출자 | 넘기는 상태 |
|---|---|
| `AccessibleDocumentQueryService` (검색) | `INDEXED` |
| `DocumentQueryService` (목록) | `DELETED`를 제외한 전체, 또는 요청한 단일 상태 |

`statuses`는 네이티브 쿼리라 `DocumentStatus.name()` 문자열 목록으로 넘긴다.

### 권한 판정과 페이징을 분리했다

권한 pre-filter로 읽을 수 있는 문서 ID를 먼저 구하고, 그 ID 집합에 대해 JPQL 페이지 쿼리로
정렬·페이징만 수행한다. 응답에 현재 버전 번호·상태를 담아야 하는데 `Document.currentVersion`이
`LAZY`라 `LEFT JOIN FETCH`로 즉시 로딩한다(`countQuery`는 별도 지정).

읽을 수 있는 문서가 하나도 없으면 페이지 쿼리를 아예 실행하지 않고 빈 응답을 반환한다.

### 정렬은 고정이다

외부 `sort` 파라미터를 받지 않고 `createdAt DESC, id DESC`로 고정한다. 정렬 키를 열어두면
인덱스 없는 컬럼 정렬 요청을 그대로 DB에 흘리게 되고, 응답 계약도 불안정해진다.

## API 명세

### 요청

```http
GET /api/documents?status=INDEXED&page=0&size=20
Authorization: Bearer {accessToken}
```

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| `status` | `DocumentStatus` | X | 없음 | 지정 시 해당 상태만 조회. 미지정 시 `DELETED` 제외 전체 |
| `page` | int | X | `0` | 0부터 시작하는 페이지 번호 |
| `size` | int | X | `20` | 페이지 크기, 1~100 |

`DocumentStatus`: `DRAFT` / `UPLOADED` / `INDEXING` / `INDEXED` / `FAILED` / `ARCHIVED` / `DELETED`

### 응답 200

```json
{
  "success": true,
  "status": 200,
  "data": {
    "content": [
      {
        "documentId": 12,
        "title": "2026 상반기 운영 가이드",
        "description": "운영팀 공유용",
        "documentType": "PDF",
        "status": "INDEXED",
        "visibility": "PRIVATE",
        "ownerUserId": 3,
        "currentVersionNo": 2,
        "currentVersionStatus": "INDEXED",
        "createdAt": "2026-08-10T09:12:33",
        "updatedAt": "2026-08-11T14:02:10"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  },
  "timestamp": "2026-08-12 19:30:00"
}
```

아직 인덱싱이 끝난 버전이 없으면 `currentVersionNo`와 `currentVersionStatus`는 `null`이다.

### 에러 케이스

| 상황 | HTTP | 응답 |
|---|---|---|
| 인증 토큰 없음 또는 만료 | 401 | 인증 실패 |
| `page < 0`, `size < 1`, `size > 100` | 400 | 제약 조건 위반 |
| `status`에 정의되지 않은 값 | 400 | 타입 변환 실패 |
| 읽을 수 있는 문서 없음 | 200 | `content: []`, `totalElements: 0` (에러 아님) |

읽을 수 있는 문서가 없는 것은 정상 상태이므로 404가 아니라 빈 페이지를 반환한다. 권한 없는 문서는
목록에서 조용히 제외되며, 존재 여부를 응답으로 노출하지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `DocumentRepository` | 권한 pre-filter 쿼리 2개 상태 파라미터화, 목록 페이지 쿼리 `findAllByIdIn` 추가 |
| `AccessibleDocumentQueryService` | 검색 호출부에서 `INDEXED` 상태를 명시적으로 전달 |
| `DocumentSummaryResponse` | 신규 응답 record |
| `DocumentSummaryConverter` | 신규 Converter |
| `DocumentQueryService` | `getMyDocuments` 추가 |
| `DocumentQueryController` | 목록 API 추가, `@Validated`로 page·size 범위 검증 |

## 테스트

**단위 — `DocumentQueryServiceTest`**

- 읽을 수 있는 문서를 페이지 응답으로 변환해 반환한다
- 읽을 수 있는 문서가 없으면 문서를 조회하지 않고 빈 페이지를 반환한다
- `status`를 지정하지 않으면 `DELETED`를 제외한 전체 상태로 조회한다
- `status`를 지정하면 해당 상태만으로 조회한다

**Repository — `DocumentReadableIdsRepositoryTest` (`@DataJpaTest`)**

- `INDEXED`만 요청하면 인덱싱 중인 문서는 제외한다
- `INDEXING`을 함께 요청하면 인덱싱 중인 문서도 반환한다
- soft delete된 문서는 `DELETED` 상태를 요청해도 제외한다
- 컬렉션 범위 조회도 요청한 상태만 반환한다

seed 데이터에 PUBLIC·INDEXED 문서가 있어 모든 사용자 조회 결과에 포함되므로, 테스트가 생성한
문서만 포함·제외로 검증한다.

**검색 회귀**

상태 조건을 파라미터화하면서 검색 경로가 바뀌지 않았는지
`AccessibleDocumentQueryServiceTest`, `SearchFacadeTest`,
`DocumentIndexingCompletionIntegrationTest`로 확인했다.

```bash
./gradlew build
```

760개 테스트 전부 통과.
