# Issue #36 문서 인덱싱 상태 조회 API 설계

## 1. 목적

문서 업로드는 원본 파일과 `PENDING` 임베딩 작업을 생성한 뒤 즉시 응답한다. 실제 파싱, 청킹,
임베딩은 비동기로 진행되므로 클라이언트가 현재 검색 가능한 버전과 처리 중인 버전을 구분해서
확인할 수 있는 조회 API가 필요하다.

```http
GET /api/documents/{documentId}/status
Authorization: Bearer {token}
```

이번 이슈는 상태를 조회만 한다. Worker 실행, 상태 변경, 인덱싱 완료, 실패·재시도는 포함하지 않는다.

## 2. 핵심 응답 계약

### 최초 버전 처리 중

최초 업로드에서는 `documents.current_version_id`가 Version 1을 가리키더라도 Version 1이 아직
검색 가능한 상태는 아니다. 따라서 `currentVersion`은 `null`이고 Version 1은
`processingVersion`으로 반환한다.

```json
{
  "documentId": 10,
  "documentStatus": "UPLOADED",
  "currentVersion": null,
  "processingVersion": {
    "versionNo": 1,
    "status": "UPLOADED",
    "jobStatus": "PENDING"
  }
}
```

### 새 버전 처리 중

Version 2가 처리되는 동안에는 기존 `INDEXED` Version 1을 검색 가능 버전으로 유지한다.

```json
{
  "documentId": 10,
  "documentStatus": "INDEXED",
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

### 처리 완료

처리 중 Version이 없으면 `processingVersion`은 `null`이다.

```json
{
  "documentId": 10,
  "documentStatus": "INDEXED",
  "currentVersion": {
    "versionNo": 2,
    "status": "INDEXED"
  },
  "processingVersion": null
}
```

## 3. 상태 판정

`currentVersion`은 `documents.current_version_id`가 가리키는 Version이 `INDEXED`일 때만 반환한다.
최초 Version이 `UPLOADED`, `PARSING`, `CHUNKED`, `EMBEDDING` 또는 `FAILED`이면 검색 가능한
버전이 아니므로 `null`이다.

처리 중 Version 상태는 기존 부분 유니크 인덱스의 조건과 동일하다.

```text
UPLOADED
PARSING
CHUNKED
EMBEDDING
```

처리 중 Version에 연결된 활성 Job 상태는 다음 둘 중 하나다.

```text
PENDING
PROCESSING
```

처리 중 Version은 있는데 활성 Job이 없거나 활성 Job이 중복되어 조회 행이 여러 개라면 정상 상태로
숨기지 않고 `INDEXING_STATUS_INCONSISTENT` 오류로 처리한다.

## 4. 조회 일관성

Document, 현재 Version, 처리 중 Version, EmbeddingJob을 각각 순차 조회하면 Worker가 상태를 바꾸는
중간에 서로 다른 시점의 값이 섞일 수 있다.

```text
Version 조회: PARSING
Job 조회: INDEXED
```

이를 방지하기 위해 하나의 JPQL Projection 쿼리로 다음 관계를 함께 조회한다.

```text
Document
LEFT JOIN current DocumentVersion
LEFT JOIN processing DocumentVersion
LEFT JOIN active EmbeddingJob
```

Projection은 조회에 필요한 ID, 문서 상태, 버전 번호, 버전 상태, Job 상태만 선택한다. Entity 전체를
Controller에 노출하지 않으며 조회 과정에서 Dirty Checking 대상 상태를 변경하지 않는다.

## 5. 권한

상태 조회에는 기존 `PermissionQueryService.canReadDocument()`를 사용한다.

```text
OWNER
PUBLIC
USER_CACHE
ROLE
DEPARTMENT
```

읽기 권한이 없으면 `PERMISSION_DENIED`를 반환하고 상태 Projection 조회를 실행하지 않는다. 존재하지
않는 문서는 `DOCUMENT_NOT_FOUND`, soft delete된 문서도 `DOCUMENT_NOT_FOUND`로 처리한다.

API는 기존 Security 설정의 `anyRequest().authenticated()` 적용을 받으므로 별도 Security 경로 변경은
필요하지 않다.

## 6. 구현 구조

```text
DocumentQueryController
→ DocumentQueryService
  → PermissionQueryService.canReadDocument()
  → DocumentRepository.findDocumentStatus()
  → DocumentStatusConverter
→ DocumentStatusResponse
```

응답 DTO는 현재 검색 가능한 버전과 처리 중 버전의 필드 차이를 명확히 하기 위해 분리한다.

```text
DocumentStatusResponse
├─ CurrentVersionStatusResponse
└─ ProcessingVersionStatusResponse
```

`ProcessingVersionStatusResponse`만 `jobStatus`를 포함한다. 외부 클라이언트가 내부 작업을 직접
조작하지 않으므로 Version ID와 Job ID는 이번 응답에 포함하지 않는다.

## 7. 오류 응답

| 상황 | HTTP | 오류 코드 |
|---|---:|---|
| 인증되지 않은 요청 | 401 | `COMMON-007` |
| 문서 없음 또는 삭제된 문서 | 404 | `DOCUMENT-001` |
| 문서 읽기 권한 없음 | 403 | `ROLE-002` |
| Version과 Job 상태 불일치 | 500 | `DOCUMENT-STATUS-001` |

상태 불일치 오류는 외부에 DB 상세를 노출하지 않고 문서 ID와 조회 행 개수만 서버 오류 로그에 남긴다.

## 8. 제외 범위와 후속 계약

- 인덱싱 완료 시 `current_version_id`를 교체하는 기능
- 실패한 Version과 Job 상태 전환
- 자동 재시도 및 Lock 만료 복구
- FAILED Version 수동 재처리
- 실패 Version 상세 조회
- Worker, 파서, 청커, 임베딩 서버 호출

후속 인덱싱 완료 기능은 새 Version을 `INDEXED`로 만들고 `current_version_id`를 교체한다. 이 API는
변경된 DB 상태를 같은 응답 계약으로 그대로 반환한다. 실패 상세는 실패·재시도 기능에서 별도 필드나
조회 계약으로 확장한다.

## 9. 테스트

- 최초 Version 처리 중 `currentVersion=null`
- Version 1 `INDEXED` 상태에서 `processingVersion=null`
- Version 1 검색 가능 상태를 유지하면서 Version 2 처리 상태 반환
- 처리 중 Version과 활성 Job 상태 함께 반환
- 처리 중 Version에 활성 Job이 없으면 상태 불일치 오류
- 활성 Job이 중복되면 상태 불일치 오류
- 읽기 권한 없음, 문서 없음, 삭제 문서 오류
- 인증된 요청과 미인증 요청의 Controller 응답
- 실제 OpenSQL 스키마에서 Projection 쿼리 검증

## 10. 완료 기준

- 읽기 권한이 있는 사용자가 문서 상태를 조회할 수 있다.
- 검색 가능한 `INDEXED` Version만 `currentVersion`으로 반환한다.
- 처리 중 Version과 Job 상태를 하나의 조회 스냅샷으로 반환한다.
- 새 Version 처리 중 기존 검색 가능 Version이 유지된다.
- 상태 불일치를 정상 응답으로 숨기지 않는다.
- 조회 과정에서 Document, Version, Job 상태를 변경하지 않는다.
- Repository, Service, Converter, Controller 테스트와 전체 빌드가 통과한다.
