# Issue #16 컬렉션 기본 CRUD 설계

## 1. 목적

문서를 그룹으로 묶어 관리하는 컬렉션(폴더/워크스페이스) 단위를 도입한다.

```text
컬렉션 생성
컬렉션 단건 조회
컬렉션에 문서 추가
```

기본 권한 단위는 컬렉션 단위(`collection_permissions`)로 부여한다. 문서 단위 권한(`document_permissions`)은 예외 케이스에만 최소한으로 사용한다.

```text
브랜치명: feature/16
```

---

## 2. 핵심 용어

### DocumentCollection

문서를 그룹화하는 논리 단위다. 폴더, 워크스페이스, 프로젝트 등 다양한 맥락에서 사용할 수 있다.

```text
java.util.Collection과의 이름 충돌을 피하기 위해 클래스명은 DocumentCollection으로 명명
테이블명은 collections
```

### CollectionDocument

컬렉션과 문서의 N:M 관계를 해소하는 중간 엔티티다.

```text
같은 문서가 여러 컬렉션에 속할 수 있다.
하나의 컬렉션에 여러 문서가 속할 수 있다.
```

---

## 3. 컬렉션 구조

### 계층 구조 (선택)

컬렉션은 다른 컬렉션을 상위로 가질 수 있다. 최상위 컬렉션은 `parent_collection_id`가 null이다.

```text
워크스페이스 (최상위, parent = null)
├─ 설계 문서 컬렉션
│   ├─ 시스템 아키텍처.md
│   └─ DB 설계.md
└─ 회의록 컬렉션
    └─ 주간 회의록.md
```

### Visibility

```text
PUBLIC  — 인증된 모든 사용자가 읽기 가능
PRIVATE — 권한이 있는 사용자만 접근 가능 (기본값)
```

`visibility` 미입력 시 `PRIVATE`으로 생성된다.

### Status

```text
ACTIVE  — 정상 사용 중
DELETED — soft delete 상태 (deleted_at 설정)
```

---

## 4. API 계약

### 컬렉션 생성

```http
POST /collections
Authorization: Bearer {token}
Content-Type: application/json
```

요청 필드:

| 필드 | 필수 | 설명 |
|---|---|---|
| `name` | 필수 | 컬렉션 이름 |
| `description` | 선택 | 컬렉션 설명 |
| `visibility` | 선택 | `PUBLIC` / `PRIVATE` (기본값: `PRIVATE`) |
| `parentCollectionId` | 선택 | 상위 컬렉션 ID (없으면 최상위) |

성공 응답 `201 Created`:

```json
{
  "id": 3,
  "name": "설계 문서",
  "description": "설계 관련 문서 모음",
  "visibility": "PRIVATE",
  "status": "ACTIVE",
  "ownerId": 1
}
```

### 컬렉션 단건 조회

```http
GET /collections/{collectionId}
Authorization: Bearer {token}
```

성공 응답 `200 OK`:

```json
{
  "id": 3,
  "name": "설계 문서",
  "description": "설계 관련 문서 모음",
  "visibility": "PRIVATE",
  "status": "ACTIVE",
  "ownerId": 1
}
```

### 컬렉션에 문서 추가

```http
POST /collections/{collectionId}/documents
Authorization: Bearer {token}
Content-Type: application/json
```

요청 필드:

| 필드 | 필수 | 설명 |
|---|---|---|
| `documentId` | 필수 | 추가할 문서 ID |

성공 응답 `201 Created`:

```json
{
  "id": 10,
  "collectionId": 3,
  "documentId": 5,
  "addedAt": "2025-07-01T10:00:00"
}
```

같은 컬렉션에 같은 문서를 이미 추가한 경우 `409 Conflict`를 반환한다.

---

## 5. 구현 구조

```text
Controller
- CollectionController
  - POST /collections
  - GET /collections/{collectionId}
  - POST /collections/{collectionId}/documents

Service
- CollectionCommandService
  - createCollection(userId, request)
  - addDocument(collectionId, userId, request)
- CollectionQueryService
  - getCollection(collectionId)

Repository
- CollectionRepository
- CollectionDocumentRepository
  - existsByCollectionIdAndDocumentId(collectionId, documentId)

Entity
- DocumentCollection
- CollectionDocument

DTO
- CreateCollectionRequest
- AddDocumentRequest
- CollectionResponse
- CollectionDocumentResponse

Converter
- CollectionConverter
```

---

## 6. 처리 흐름

### 컬렉션 생성

```text
요청 사용자 인증
        ↓
parentCollectionId가 있으면 상위 컬렉션 존재 확인
        ↓
visibility 미입력이면 PRIVATE 설정
        ↓
DocumentCollection 생성 (status = ACTIVE)
        ↓
201 Created 반환
```

### 문서 추가

```text
컬렉션 존재 확인
        ↓
컬렉션 쓰기 권한 확인 (canWriteCollection)
        ↓
문서 존재 확인
        ↓
이미 추가된 문서인지 확인 → 409
        ↓
CollectionDocument 생성
        ↓
201 Created 반환
```

---

## 7. 오류 응답

| 상황 | HTTP | 오류 코드 |
|---|---:|---|
| 컬렉션 없음 | 404 | `COLLECTION_NOT_FOUND` |
| 문서 없음 | 404 | `DOCUMENT_NOT_FOUND` |
| 쓰기 권한 없음 | 403 | `PERMISSION_DENIED` |
| 이미 추가된 문서 | 409 | `COLLECTION_DOCUMENT_ALREADY_EXISTS` |

---

## 8. 완료 기준

- 컬렉션을 생성하면 요청 사용자가 소유자(owner)로 설정된다.
- `visibility` 미입력 시 `PRIVATE`으로 생성된다.
- `parentCollectionId` 입력 시 존재하지 않는 컬렉션이면 404를 반환한다.
- 컬렉션에 같은 문서를 중복 추가하면 409를 반환한다.
- 컬렉션 쓰기 권한이 없는 사용자가 문서를 추가하면 403을 반환한다.
