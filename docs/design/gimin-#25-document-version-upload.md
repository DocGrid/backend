# Issue #25 문서 새 버전 업로드 설계

## 1. 목적

기존 논리 문서에 수정된 파일을 새 버전으로 등록한다.

```text
documents는 재사용
document_versions는 version_no 증가
file_objects는 파일 내용 기준으로 저장 또는 재사용
embedding_jobs는 새 버전마다 PENDING으로 생성
current_version_id는 새 버전이 INDEXED될 때까지 기존 버전 유지
```

이 API에서는 파싱, 청킹, 임베딩을 수행하지 않는다. 새 버전의 원본 파일과 인덱싱 Job만 접수한다.

```text
GitHub Issue: #25
```

---

## 2. 핵심 용어

### Document

사용자가 관리하는 논리적인 문서다. 제목, 공개 범위, 소유자와 현재 검색 가능한 버전을 관리한다.

```text
예: 회사 복무 규정
```

### DocumentVersion

한 Document의 특정 시점 내용이다.

```text
회사 복무 규정
├─ Version 1: 최초 규정
├─ Version 2: 출근 시간 변경
└─ Version 3: 휴가 조항 추가
```

### FileObject

MinIO에 저장된 실제 파일 바이너리를 나타낸다. 파일 내용이 완전히 같으면 여러 DocumentVersion이 하나의 FileObject를 공유할 수 있다.

---

## 3. 새 문서와 새 버전의 구분

서버는 파일명, 제목, 파일 크기 또는 해시만으로 같은 논리 문서인지 자동 판단하지 않는다. 사용자의 작업 맥락과 `documentId`가 문서의 동일성을 결정한다.

```text
문서 목록 화면의 "새 문서 업로드"
→ POST /api/documents
→ 새로운 Document와 Version 1 생성

문서 상세 화면의 "새 버전 업로드"
→ POST /api/documents/{documentId}/versions
→ 기존 Document에 다음 Version 생성
```

동일한 파일 내용도 서로 다른 제목, 권한, 컬렉션과 변경 이력을 가진 별도 문서로 사용할 수 있다.

```text
동일 파일 + POST /api/documents
→ 새로운 논리 Document 생성
→ 기존 FileObject가 있으면 재사용

POST /api/documents/{documentId}/versions
→ documentId가 지정한 기존 Document의 버전 추가 요청
→ 현재 버전과 내용이 같으면 409 Conflict
→ 현재 버전과 내용이 다르면 새 DocumentVersion 생성
```

---

## 4. 동일 파일 판정

동일 파일 판정은 파일의 실제 바이트 전체로 계산한 SHA-256과 파일 크기를 사용한다.

```text
해시 알고리즘: SHA-256
계산 대상: 파일의 처음부터 끝까지 실제 바이트 전체
중복 기준: file_hash + file_size
```

해시 계산에 포함하지 않는 값:

- 파일명
- 문서 제목
- 설명
- documentId
- 업로드 사용자

판정 규칙:

```text
hash 다름 + size 같음
→ 다른 파일

hash 같음 + size 같음
→ 같은 파일 내용
```

줄바꿈 방식, 문자 인코딩, BOM 또는 공백이 다르면 실제 바이트가 다르므로 해시도 달라질 수 있다.

### 현재 버전과 동일한 내용

```text
업로드 파일의 hash + size
= currentVersion 파일의 hash + size

→ 409 Conflict
→ DocumentVersion 생성 안 함
→ EmbeddingJob 생성 안 함
→ MinIO 저장 안 함
```

오류 코드:

```text
DOCUMENT_VERSION_SAME_CONTENT
현재 버전과 동일한 파일입니다.
```

### 과거 버전과 같고 현재 버전과 다른 내용

과거 내용으로 되돌리는 의미가 있으므로 새 버전을 생성한다.

```text
Version 1 → FileObject A
Version 2 → FileObject B (현재)
Version 3 → FileObject A 재사용
```

Version 3과 EmbeddingJob은 새로 생성하지만 실제 파일은 FileObject A를 재사용한다.

---

## 5. API 계약

```http
POST /api/documents/{documentId}/versions
Authorization: Bearer {token}
Content-Type: multipart/form-data
```

요청 필드:

| 필드 | 필수 | 설명 |
|---|---|---|
| `file` | 필수 | 수정된 TXT 또는 Markdown 파일 |

```java
public record DocumentVersionUploadRequest(
    @NotNull MultipartFile file
) {
}
```

이번 구현은 파일 내용의 버전만 관리한다. 제목과 설명은 Document 공통 메타데이터이며 별도 메타데이터 수정 API에서 변경한다.

```text
새 Version.title_snapshot
→ Version 접수 시점의 Document.title 복사

Version 처리 중 별도 API로 Document.title 변경
→ 후속 인덱싱 완료 처리에서 title_snapshot으로 덮어쓰지 않음
```

`title_snapshot`은 해당 Version이 접수될 당시의 제목을 기록하는 감사·출처용 스냅샷이다. 현재 문서 제목을 교체하기 위한 후보 값으로 사용하지 않는다. 추후 제목이나 설명 자체를 버전 자산으로 관리해야 한다면 메타데이터 revision과 `description_snapshot`을 포함한 별도 설계를 추가한다.

### 성공 응답

`201 Created`를 반환한다.

```json
{
  "documentId": 10,
  "documentVersionId": 21,
  "versionNo": 2,
  "embeddingJobId": 30,
  "currentVersionId": 20,
  "documentStatus": "INDEXED",
  "versionStatus": "UPLOADED",
  "jobStatus": "PENDING"
}
```

전역 FileObject 공유 여부가 외부에 노출되지 않도록 `fileObjectId`, `bucketName`, `objectKey`는 사용자 API 응답에서 제외한다.

---

## 6. 버전별 파일 메타데이터

FileObject는 실제 바이너리 하나를 나타내므로 여러 문서가 공유할 수 있다. 따라서 문서별 업로드 파일명과 Content-Type을 FileObject에만 저장하면 두 번째 업로드의 메타데이터가 유실된다.

```text
개발팀양식.md → 내용 abc
인사팀양식.md → 내용 abc

FileObject는 하나
각 Version의 original_filename은 별도 보존
```

역할을 다음과 같이 분리한다.

```text
file_objects
- file_hash
- file_size
- bucket_name
- object_key
- storage_provider
- 최초 저장 감사 정보

document_versions
- document_id
- file_object_id
- original_filename
- content_type
- title_snapshot
- created_by
```

이슈 #25 마이그레이션에서 다음 컬럼을 추가한다.

```sql
ALTER TABLE document_versions
    ADD COLUMN original_filename VARCHAR(500),
    ADD COLUMN content_type VARCHAR(200);
```

두 컬럼은 nullable로 유지한다. `document_versions.file_object_id`가 nullable이고 URL/API/MCP 기반 Version에는 업로드 파일이 없을 수 있기 때문이다.

```text
UPLOAD Version
→ 애플리케이션에서 original_filename과 content_type 설정

URL / API / MCP Version
→ null 허용
```

기존 데이터는 `file_object_id`가 있는 행만 연결된 `file_objects` 값으로 백필한다. `file_objects.original_filename`과 `content_type`은 기존 호환성을 위해 이번 PR에서는 제거하지 않는다.

이슈 #25 적용 이후에는 두 업로드 경로가 모두 Version 메타데이터를 채워야 한다.

```text
DocumentUploadService
→ 신규 Document의 Version 1에 original_filename/content_type 저장

DocumentVersionUploadService
→ 기존 Document의 후속 Version에 original_filename/content_type 저장
```

---

## 7. 파일 형식 정책

현재 `document_type`은 documents에만 있으므로 새 버전은 기존 문서와 같은 타입만 허용한다.

```text
MD → MD: 허용
TXT → TXT: 허용
MD → TXT: 거절
TXT → MD: 거절
```

오류:

```text
400 Bad Request
DOCUMENT_VERSION_TYPE_MISMATCH
기존 문서와 동일한 파일 형식만 업로드할 수 있습니다.
```

버전마다 타입 변경을 허용하려면 추후 `document_versions.document_type_snapshot`을 추가해야 한다.

---

## 8. 권한 및 보안 정책

현재 MVP에서는 문서 소유자만 새 버전을 생성한다.

```text
document.owner_user_id = 현재 인증 사용자
→ 허용

다른 사용자
→ 403 Forbidden
```

향후 통합 권한 판정 서비스가 준비되면 `OWNER 또는 can_write=true`로 확장한다.

FileObject를 공유하더라도 FileObject ID로 직접 다운로드하게 해서는 안 된다.

```text
documentId 또는 documentVersionId 요청
→ 문서 접근 권한 확인
→ 연결된 FileObject 조회
→ 다운로드 URL 발급
```

문서 권한을 거치지 않는 `GET /file-objects/{fileObjectId}` 형태의 사용자 API는 제공하지 않는다.

---

## 9. 버전 생성 허용 조건

파일 버전 API는 `documents.source_type = UPLOAD`인 문서에만 허용한다. URL/API/MCP 문서는 FileObject가 없을 수 있으므로 각 Source 전용 갱신 흐름을 사용한다.

기존 검색 가능한 Version이 있는 문서는 다음 조건을 모두 만족해야 한다.

```text
documents.status = INDEXED
currentVersion.status = INDEXED
documents.source_type = UPLOAD
새로운 처리 중 Version 없음
요청 사용자가 문서 소유자
새 파일 타입 = 기존 document_type
새 파일 내용 != currentVersion 파일 내용
```

처리 중 Version 상태:

```text
UPLOADED
PARSING
CHUNKED
EMBEDDING
```

처리 중 Version이 있으면:

```text
409 Conflict
DOCUMENT_VERSION_IN_PROGRESS
처리 중인 문서 버전이 있습니다.
```

상태별 정책:

| Document 상태 | 새 버전 | 정책 |
|---|---|---|
| `INDEXED` | 허용 | 기존 검색 가능 Version 유지 |
| `UPLOADED`, `INDEXING` | 거절 | 처리 중 Version 존재 |
| `FAILED` | 조건부 허용 | 수정된 파일로 복구 가능 |
| `ARCHIVED` | 거절 | 명시적 복원 후 업로드 |
| `DELETED` | 거절 | 삭제 문서 변경 금지 |
| `DRAFT` | 거절 | 현재 업로드 흐름 대상 아님 |

최초 Version이 실패해 검색 가능한 Version이 없는 경우에도 다음 조건이면 수정 파일을 Version 2로 등록할 수 있다.

```text
documents.status = FAILED
documents.source_type = UPLOAD
처리 중 Version 없음
업로드 파일 내용 != 최신 FAILED Version 파일 내용

→ 새 Version 생성
→ documents.status = UPLOADED
→ 기존 FAILED current_version_id는 새 Version 완료 전까지 유지
```

최신 FAILED Version과 같은 파일을 다시 전송하면 새 Version을 만들지 않고 별도의 수동 재처리 기능을 사용한다. FAILED Version 뒤에 더 최신 Version이 생성되면 과거 FAILED Version은 수동 재처리할 수 없다.

---

## 10. 정상 처리 흐름

```text
인증 사용자 요청
        ↓
파일 유효성 검사
        ↓
파일 바이트 전체 SHA-256 + size 계산
        ↓
Document 존재 및 소유자 사전 확인
        ↓
문서 상태·source_type·파일 타입 사전 확인
        ↓
현재 Version과 동일 내용인지 사전 확인
        ↓
기존 FileObject 조회
        ↓
없으면 MinIO 후보 Object 저장
        ↓
DB 트랜잭션 시작
        ↓
Document 행 SELECT FOR UPDATE
        ↓
소유자·상태·source_type·처리 중 Version 재검증
        ↓
현재 Version과 동일 내용인지 재검증
        ↓
FileObject 저장 또는 재사용
        ↓
다음 version_no 계산
        ↓
새 DocumentVersion 생성
        ↓
active EmbeddingModel 조회
        ↓
EmbeddingJob PENDING 생성
        ↓
DB Commit
        ↓
사용되지 않은 MinIO 후보 Object 정리
        ↓
201 Created 반환
```

사전 검증은 불필요한 MinIO 업로드를 줄이고, 트랜잭션 내부 재검증은 동시 요청에 의한 상태 변경을 방지한다.

---

## 11. DB 저장 결과

기존 상태:

```text
documents.id = 10
documents.status = INDEXED
documents.title = 회사 복무 규정
documents.current_version_id = 20

document_versions.id = 20
document_versions.document_id = 10
document_versions.version_no = 1
document_versions.status = INDEXED
```

Version 2 접수 후:

```text
documents.id = 10
documents.status = INDEXED
documents.title = 회사 복무 규정
documents.current_version_id = 20

document_versions.id = 21
document_versions.document_id = 10
document_versions.version_no = 2
document_versions.title_snapshot = 회사 복무 규정
document_versions.status = UPLOADED

embedding_jobs.document_version_id = 21
embedding_jobs.status = PENDING
```

Version 2가 INDEXED되면 후속 인덱싱 완료 처리에서:

```text
documents.current_version_id = 21
documents.status = INDEXED
```

Version 2가 실패하면:

```text
documents.current_version_id = 20 유지
documents.status = INDEXED 유지
document_versions.id = 21
document_versions.status = FAILED
```

---

## 12. 동시성 및 DB 제약

### version_no 원자적 증가

```text
1. documents 행 SELECT FOR UPDATE
2. 해당 문서의 MAX(version_no) 조회
3. nextVersionNo = maxVersionNo + 1
4. DocumentVersion INSERT
```

기존 제약을 최종 안전장치로 유지한다.

```text
UNIQUE(document_id, version_no)
```

### 처리 중 Version 하나 제한

```sql
CREATE UNIQUE INDEX uk_document_versions_one_in_progress
    ON document_versions (document_id)
    WHERE status IN ('UPLOADED', 'PARSING', 'CHUNKED', 'EMBEDDING');
```

마이그레이션 적용 전에 동일 문서에 처리 중 Version이 여러 개 존재하는지 검사한다. 인덱스 충돌은 `DOCUMENT_VERSION_IN_PROGRESS` 409로 변환한다.

### current_version 단조 증가

후속 인덱싱 완료 처리에서는 Document를 Lock하고 버전 번호를 비교한다.

```text
완료 Version.version_no > currentVersion.version_no
→ current_version_id 교체

완료 Version.version_no <= currentVersion.version_no
→ stale completion으로 409 Conflict
→ Version, Job, current_version_id 모두 변경하지 않음
```

stale 완료 오류는 `INDEXING_STALE_VERSION_COMPLETION`을 사용한다. Claim Token 검증과 별개로 오래된 완료 요청의 상태 쓰기를 최종 차단한다.

후속 수동 재처리 기능에서는 FAILED Version보다 최신 Version이 이미 존재하면 재처리를 거부한다.

---

## 13. MinIO와 DB 정합성

MinIO 저장과 DB 트랜잭션은 하나로 묶이지 않는다.

```text
새 MinIO Object 저장 성공
DB 저장 실패
→ 이번 요청이 생성한 후보 Object 삭제 시도
```

후보 Object와 최종 채택된 공유 Object를 구분한다.

```text
requestCandidate != null
→ 이번 요청이 새로 올린 MinIO 후보 Object

DB 트랜잭션 실패
→ requestCandidate가 있으면 항상 삭제 시도

DB 성공 + candidateClaimed = true
→ requestCandidate가 FileObject로 채택됨
→ 삭제 금지

DB 성공 + candidateClaimed = false
→ 동시 요청이 만든 다른 FileObject를 재사용
→ 이번 요청의 requestCandidate는 미사용이므로 삭제

처음부터 기존 FileObject 발견
→ requestCandidate 없음
→ 삭제할 후보 없음
```

`candidateClaimed=false`는 공유 Object를 삭제하라는 뜻이 아니다. 이번 요청이 별도로 업로드했지만 채택되지 않은 후보 Object만 삭제한다.

프로세스가 MinIO 저장 직후 강제 종료되면 보상 로직이 실행되지 않을 수 있다. 일정 시간 이상 DB와 연결되지 않은 Object를 정리하는 운영 작업은 후속 범위로 기록한다.

---

## 14. 오류 응답

| 상황 | HTTP | 오류 코드 |
|---|---:|---|
| 문서 없음 | 404 | `DOCUMENT_NOT_FOUND` |
| 소유자가 아님 | 403 | `PERMISSION_DENIED` |
| 현재 버전과 동일 파일 | 409 | `DOCUMENT_VERSION_SAME_CONTENT` |
| 처리 중 Version 존재 | 409 | `DOCUMENT_VERSION_IN_PROGRESS` |
| 문서가 버전 생성 가능 상태가 아님 | 409 | `DOCUMENT_VERSION_NOT_ALLOWED` |
| UPLOAD Source 문서가 아님 | 409 | `DOCUMENT_VERSION_NOT_ALLOWED` |
| 기존 문서와 파일 형식 불일치 | 400 | `DOCUMENT_VERSION_TYPE_MISMATCH` |
| 파일 검증 실패 | 400 | 기존 `DOCUMENT-FILE-*` |
| MinIO 실패 | 503 | `FILE_STORAGE_FAILED` |
| active 모델 없음 | 500 | `EMBEDDING_MODEL_NOT_CONFIGURED` |
| 최신 Version보다 오래된 완료 요청 | 409 | `INDEXING_STALE_VERSION_COMPLETION` |

---

## 15. 필요한 구성요소

```text
Controller
- DocumentUploadController
  - POST /api/documents/{documentId}/versions

DTO
- DocumentVersionUploadRequest
- DocumentVersionUploadResponse

Service
- DocumentVersionUploadFacade
- DocumentVersionUploadService
- DocumentVersionUploadCommand
- DocumentVersionUploadTransactionResult

Repository
- DocumentRepository.findByIdForUpdate(...)
- DocumentVersionRepository.findMaxVersionNo(...)
- DocumentVersionRepository.existsInProgressVersion(...)
- FileObjectRepository 기존 중복 조회/INSERT 로직 재사용
- EmbeddingJobRepository

Migration
- document_versions nullable 업로드 메타데이터 컬럼 추가 및 가능한 행 백필
- 처리 중 Version 부분 유니크 인덱스 추가
```

FileObject 저장·재사용 로직은 신규 문서 업로드와 버전 업로드가 함께 사용하므로 작은 공통 서비스로 추출한다. 기존 `DocumentUploadService`도 Version 1의 `original_filename`과 `content_type`을 저장하도록 수정한다. 그 외 업로드 흐름은 불필요하게 재구성하지 않는다.

---

## 16. 후속 기능 계약

### 문서 상태 조회

현재 검색 가능한 버전과 처리 중 버전을 함께 반환한다.

```json
{
  "currentVersion": {
    "versionNo": 1,
    "status": "INDEXED"
  },
  "processingVersion": {
    "versionNo": 2,
    "status": "PARSING",
    "jobStatus": "PROCESSING"
  }
}
```

### 인덱싱 완료

- 완료 Version이 현재 Version보다 최신인지 검증한다.
- 최신 Version이면 `current_version_id`를 교체한다.
- `documents.title`은 별도 메타데이터이므로 완료 처리에서 덮어쓰지 않는다.
- 오래된 Version 완료가 현재 Version을 덮어쓰지 못하게 한다.
- stale 완료 요청은 Version과 Job 상태를 포함해 아무것도 변경하지 않고 409로 거절한다.

### 실패 및 자동 재시도

- 새 Version 실패 시 기존 current Version을 유지한다.
- 재시도 중에도 기존 INDEXED Version을 검색 가능하게 유지한다.

### 수동 재처리

- FAILED Version보다 최신 Version이 존재하면 오래된 Version 재처리를 거부한다.

### E2E 검증

- Version 2 처리 중 Version 1 검색 가능 여부
- Version 2 성공 후 current Version 교체
- Version 2 실패 후 Version 1 유지
- 오래된 Version 완료·재처리에 의한 역행 방지

---

## 17. 테스트 케이스

1. INDEXED 문서에 수정 파일 업로드 시 같은 document_id에 version_no=2가 생성된다.
2. 새 Version 접수 후 기존 current_version_id가 유지된다.
3. 새 Version에 PENDING EmbeddingJob이 생성된다.
4. 현재 Version과 동일한 파일이면 409이고 Version과 Job이 생성되지 않는다.
5. 과거 Version 파일로 되돌리면 새 Version을 생성하고 기존 FileObject를 재사용한다.
6. 동일 파일이 다른 Document에 사용되면 FileObject만 재사용한다.
7. 동일 내용·다른 파일명의 각 original_filename이 Version별로 보존된다.
8. 처리 중 Version이 있으면 409를 반환한다.
9. 최초 Version이 아직 UPLOADED이면 새 Version을 거절한다.
10. 문서와 다른 파일 형식이면 400을 반환한다.
11. 소유자가 아닌 사용자는 403을 반환한다.
12. 동시에 서로 다른 파일을 업로드하면 하나만 성공한다.
13. 동시에 같은 파일을 업로드해도 Version은 하나만 생성된다.
14. DB 실패 시 새 MinIO 후보 Object를 보상 삭제한다.
15. 기존 FileObject 재사용 중 DB 실패 시 공유 Object를 삭제하지 않는다.
16. 신규 문서 Version 1에 original_filename과 content_type이 저장된다.
17. 기존 문서 후속 Version에 original_filename과 content_type이 저장된다.
18. Version 접수 시 현재 Document.title이 title_snapshot에 복사된다.
19. Version 처리 중 별도 API로 제목을 바꿔도 완료 시 title_snapshot으로 덮어쓰지 않는다.
20. 새 Version 완료 시 current_version_id가 변경된다.
21. 새 Version 실패 시 기존 current_version_id가 유지된다.
22. 최초 Version이 FAILED이고 다른 수정 파일을 업로드하면 Version 2 생성을 허용한다.
23. 최초 Version이 FAILED이고 동일 파일을 업로드하면 409로 재처리를 안내한다.
24. source_type이 URL/API/MCP인 문서의 파일 버전 업로드를 거절한다.
25. 최신 Version이 존재하면 과거 FAILED Version 재처리를 거부한다.
26. 오래된 Version 완료 요청은 모든 상태를 유지하고 409로 거절한다.
27. 동시 업로드에서 패배한 요청의 미사용 MinIO 후보 Object를 삭제한다.
28. file_object_id가 null인 기존 Version이 있어도 메타데이터 마이그레이션이 성공한다.

---

## 18. 완료 기준

- 신규 문서 생성 API와 버전 추가 API가 분리된다.
- 문서 동일성은 documentId와 사용자 작업 의도로 결정된다.
- 동일 파일 판정은 파일 바이트 전체의 SHA-256과 크기로 수행된다.
- 기존 documents 행을 재사용하고 version_no를 원자적으로 증가시킨다.
- 동시에 두 개의 처리 중 Version이 생성되지 않는다.
- 현재 Version과 동일한 내용은 중복 Version으로 저장되지 않는다.
- 실제 파일은 해시 기준으로 중복 저장되지 않는다.
- UPLOAD Version의 original_filename과 content_type이 Version에 보존된다.
- 파일이 없는 Source의 Version 메타데이터 컬럼은 null을 허용한다.
- 새 Version 처리 중 기존 current_version_id가 유지된다.
- 새 Version이 INDEXED되면 current_version_id가 교체된다.
- Version 완료 처리는 별도 메타데이터 변경을 덮어쓰지 않는다.
- 오래된 Version이 최신 current Version을 덮어쓰지 못한다.
- 최초 Version 실패 후 수정 파일로 새 Version을 생성할 수 있다.
- 파일 버전 API는 UPLOAD Source에만 허용된다.
- 새 Version마다 별도의 PENDING Job이 생성된다.
- DB 실패 또는 동시성 패배 시 이번 요청의 미사용 MinIO 후보만 삭제된다.
- FileObject 직접 접근 없이 문서 권한을 거쳐 파일에 접근한다.
- 문서 상태 조회, 인덱싱 완료, 실패·재시도, 수동 재처리, E2E 검증과의 계약이 문서화된다.
