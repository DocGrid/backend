# Issue #36 문서 인덱싱 상태 조회 API 설계

## 1. 목적

문서 업로드는 원본 파일과 `PENDING` 상태의 임베딩 작업을 생성한 뒤 즉시 응답한다.

실제 파싱, 청킹, 임베딩은 비동기로 진행되므로 클라이언트가 다음 두 상태를 구분해서 확인할 수 있는 조회 API가 필요하다.

- 현재 검색에 사용할 수 있는 버전
- 새롭게 처리 중인 버전

```http
GET /api/documents/{documentId}/status
Authorization: Bearer {token}
```

이번 이슈는 상태를 조회하는 기능만 구현한다.

다음 기능은 이번 이슈에 포함하지 않는다.

- Worker 실행
- 문서 및 작업 상태 변경
- 인덱싱 완료 처리
- 실패 및 재시도 처리

---

## 2. 핵심 응답 계약

### 2.1 최초 버전 처리 중

최초 업로드에서는 `documents.current_version_id`가 Version 1을 가리키더라도 Version 1이 아직 검색 가능한 상태는 아니다.

따라서 `currentVersion`은 `null`이고, Version 1은 `processingVersion`으로 반환한다.

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

### 2.2 새 버전 처리 중

Version 2가 처리되는 동안에는 기존에 인덱싱이 완료된 Version 1을 검색 가능한 버전으로 유지한다.

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

### 2.3 처리 완료

Version 2의 인덱싱이 완료되고 `current_version_id`가 Version 2로 교체되면 Version 2를 `currentVersion`으로 반환한다.

처리 중인 Version이 없으므로 `processingVersion`은 `null`이다.

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

---

## 3. Document, DocumentVersion, EmbeddingJob의 역할

### 3.1 Document

`Document`는 사용자가 관리하는 논리적인 문서 자체를 나타낸다.

예를 들어 사용자가 `보고서.pdf`를 업로드하고 이후 수정된 파일을 다시 업로드해도 논리적으로는 같은 문서다.

```text
Document 10
├─ Version 1
├─ Version 2
└─ Version 3
```

### 3.2 DocumentVersion

`DocumentVersion`은 특정 시점에 업로드된 문서의 실제 내용을 나타낸다.

```text
Document
= 하나의 논리적인 문서

DocumentVersion
= 해당 문서의 특정 업로드본 또는 수정본
```

예를 들어 Version 1이 현재 검색에 사용되고 있는 상태에서 사용자가 수정된 파일을 업로드하면 Version 2가 새롭게 생성된다.

```text
Document 10
├─ Version 1 / INDEXED
└─ Version 2 / PARSING
```

`DocumentVersion.status`는 해당 버전이 전체 문서 처리 파이프라인에서 어느 단계에 있는지를 나타낸다.

```text
UPLOADED
→ PARSING
→ CHUNKED
→ EMBEDDING
→ INDEXED
```

각 상태의 의미는 다음과 같다.

```text
UPLOADED
= 원본 파일과 DocumentVersion이 생성된 상태

PARSING
= 원본 파일에서 텍스트를 추출하는 상태

CHUNKED
= 추출한 텍스트를 검색 단위로 분할한 상태

EMBEDDING
= 청크를 벡터로 변환하고 벡터 저장소에 저장하는 상태

INDEXED
= 검색에 사용할 수 있도록 모든 처리가 완료된 상태

FAILED
= 문서 처리 과정이 최종적으로 실패한 상태
```

### 3.3 EmbeddingJob

`EmbeddingJob`은 특정 `DocumentVersion`을 처리하기 위해 생성되는 비동기 작업 단위다.

```text
DocumentVersion 2
└─ EmbeddingJob
```

`EmbeddingJob.status`는 문서의 전체 처리 단계가 아니라 비동기 작업 자체의 실행 상태를 나타낸다.

```text
PENDING
= Worker가 아직 작업을 가져가지 않은 대기 상태

PROCESSING
= Worker가 작업을 가져가 실제로 실행 중인 상태

INDEXED
= 작업이 성공적으로 완료된 상태

RETRY_PENDING
= 이전 작업이 실패하거나 만료되어 재시도를 기다리는 상태

FAILED
= 허용된 재시도를 모두 사용하고 최종 실패한 상태

CANCELED
= 작업이 취소된 상태
```

### 3.4 Version과 Job이 동시에 존재하는 이유

`DocumentVersion`과 `EmbeddingJob`은 같은 상태를 중복해서 표현하는 것이 아니다.

```text
DocumentVersion 상태
= 문서 데이터가 전체 인덱싱 파이프라인에서 어느 단계에 있는가

EmbeddingJob 상태
= 해당 비동기 작업이 대기 중인지, 실제 실행 중인지
```

따라서 다음 상태는 서로 모순되지 않는다.

```text
DocumentVersion 2 / EMBEDDING
└─ EmbeddingJob / PROCESSING
```

이는 다음과 같은 의미다.

```text
Version 2는 전체 문서 처리 과정에서 임베딩 단계에 있고,
해당 임베딩 작업은 Worker가 실제로 수행 중이다.
```

Version은 처리 대상과 전체 진행 단계를 표현하고, Job은 해당 처리를 수행하는 작업의 실행 상태를 표현한다.

비유하면 다음과 같다.

```text
DocumentVersion
= 배송할 물건

EmbeddingJob
= 해당 물건을 배송하는 업무

EmbeddingJobAttempt
= 1차 배송 시도, 2차 배송 시도
```

각 모델의 책임은 다음과 같다.

```text
DocumentVersion
- 특정 문서 버전의 데이터 관리
- 전체 인덱싱 처리 단계 관리
- 최종 검색 가능 여부 판단

EmbeddingJob
- 비동기 작업의 대기 및 실행 상태 관리
- 작업 재시도 가능 여부 관리
- 작업 생명주기 관리

EmbeddingJobAttempt
- Worker가 수행한 개별 실행 시도 기록
- 성공, 실패, 타임아웃, 중단 이력 관리
```

현재 DocGrid 구조에서는 다음과 같은 관계를 가진다.

```text
Document
└─ DocumentVersion
   └─ EmbeddingJob
      ├─ EmbeddingJobAttempt 1
      ├─ EmbeddingJobAttempt 2
      └─ EmbeddingJobAttempt 3
```

이번 상태 조회 API에서는 외부 클라이언트가 이해해야 하는 최소 상태만 반환한다.

따라서 다음 정보만 응답에 포함한다.

```text
Document 상태
현재 검색 가능한 Version 상태
처리 중인 Version 상태
활성 EmbeddingJob 상태
```

Worker ID, Job ID, Attempt 정보와 같은 내부 실행 정보는 응답에 포함하지 않는다.

---

## 4. 상태 판정

### 4.1 Current Version 판정

`currentVersion`은 `documents.current_version_id`가 가리키는 Version이 `INDEXED`일 때만 반환한다.

```text
current_version_id가 가리키는 Version 상태 = INDEXED
→ currentVersion 반환

current_version_id가 가리키는 Version 상태 ≠ INDEXED
→ currentVersion null
```

최초 Version이 다음 상태라면 아직 검색 가능한 버전이 아니다.

```text
UPLOADED
PARSING
CHUNKED
EMBEDDING
FAILED
```

따라서 `documents.current_version_id`가 해당 Version을 가리키더라도 응답의 `currentVersion`은 `null`이다.

### 4.2 Processing Version 판정

처리 중인 Version 상태는 기존 부분 유니크 인덱스의 조건과 동일하다.

```text
UPLOADED
PARSING
CHUNKED
EMBEDDING
```

다음 상태는 처리 중 Version에 포함하지 않는다.

```text
INDEXED
FAILED
DELETED
```

문서당 처리 중 Version은 최대 하나만 존재해야 한다.

```text
Document 10
├─ Current Version 1 / INDEXED
└─ Processing Version 2 / PARSING
```

### 4.3 Active EmbeddingJob 판정

처리 중 Version에 연결된 활성 Job 상태는 다음과 같다.

```text
PENDING
PROCESSING
```

처리 중 Version이 존재한다면 활성 Job도 정확히 하나 존재해야 한다.

```text
Processing Version
└─ Active EmbeddingJob 1개
```

### 4.4 정상 상태 조합

상태 조합은 다음과 같이 해석한다.

| Version 상태 | Job 상태 | 의미 |
|---|---|---|
| `UPLOADED` | `PENDING` | 파일 업로드가 완료되었고 Worker 실행을 기다리는 상태 |
| `UPLOADED` | `PROCESSING` | Worker가 작업을 가져가 초기 처리를 시작한 상태 |
| `PARSING` | `PROCESSING` | Worker가 파일에서 텍스트를 추출하는 상태 |
| `CHUNKED` | `PROCESSING` | 텍스트 분할이 완료되었고 후속 처리가 진행 중인 상태 |
| `EMBEDDING` | `PROCESSING` | Worker가 청크 임베딩과 벡터 저장을 수행 중인 상태 |
| `INDEXED` | 활성 Job 없음 | 검색 가능한 상태이며 진행 중인 작업이 없는 상태 |

Version 상태와 Job 상태가 항상 완전히 동일하게 변경될 필요는 없다.

Version 상태는 전체 처리 단계를 나타내고 Job 상태는 비동기 작업 실행 여부를 나타내기 때문이다.

### 4.5 상태 불일치 판정

처리 중 Version은 있지만 활성 Job이 없으면 정상 상태로 숨기지 않는다.

```text
Processing Version 존재
Active EmbeddingJob 없음
→ INDEXING_STATUS_INCONSISTENT
```

활성 Job이 중복되어 조회 결과가 여러 행으로 반환되는 경우에도 상태 불일치 오류로 처리한다.

```text
Processing Version 1개
Active EmbeddingJob 2개
→ INDEXING_STATUS_INCONSISTENT
```

처리 중 Version 자체가 중복된 경우에도 상태 불일치 오류로 처리한다.

```text
Processing Version 2개
→ INDEXING_STATUS_INCONSISTENT
```

이러한 상태는 임의로 하나를 선택하거나 정상 응답으로 변환하지 않는다.

---

## 5. 조회 일관성

Document, 현재 Version, 처리 중 Version, EmbeddingJob을 각각 순차적으로 조회하면 조회 중간에 Worker가 상태를 변경할 수 있다.

```text
1. Document 조회
2. Current Version 조회
3. Processing Version 조회
4. EmbeddingJob 조회
```

예를 들어 애플리케이션이 Version을 먼저 조회한 직후 Worker가 인덱싱을 완료할 수 있다.

```text
T1: Version 조회
    → PARSING

T2: Worker가 상태 변경
    → Version = INDEXED
    → Job = INDEXED

T3: Job 조회
    → INDEXED
```

그러면 응답에는 다음과 같이 서로 다른 시점의 값이 섞일 수 있다.

```text
Version 상태: PARSING
Job 상태: INDEXED
```

각각의 값은 실제 DB에 존재했던 값이지만 같은 시점의 상태는 아니다.

이를 방지하기 위해 하나의 JPQL Projection 쿼리로 다음 관계를 함께 조회한다.

```text
Document
LEFT JOIN current DocumentVersion
LEFT JOIN processing DocumentVersion
LEFT JOIN active EmbeddingJob
```

개념적인 조회 관계는 다음과 같다.

```text
Document
├─ Current DocumentVersion
└─ Processing DocumentVersion
   └─ Active EmbeddingJob
```

하나의 쿼리로 조회하면 Document, Version, Job 상태를 동일한 SELECT 문장의 조회 결과로 가져올 수 있다.

여러 Repository 메서드를 순차 호출하는 것보다 조회 사이에 서로 다른 시점의 상태가 섞이는 문제를 줄일 수 있다.

### 5.1 LEFT JOIN을 사용하는 이유

현재 Version, 처리 중 Version, 활성 Job은 항상 존재하는 것이 아니다.

최초 Version 처리 중에는 검색 가능한 현재 Version이 없다.

```text
Document 존재
Current Version 없음
Processing Version 존재
Active Job 존재
```

모든 처리가 완료된 이후에는 처리 중 Version과 활성 Job이 없다.

```text
Document 존재
Current Version 존재
Processing Version 없음
Active Job 없음
```

`INNER JOIN`을 사용하면 연결된 데이터가 없는 경우 Document 자체가 조회되지 않을 수 있다.

따라서 선택적으로 존재하는 데이터를 포함해 Document를 조회할 수 있도록 `LEFT JOIN`을 사용한다.

### 5.2 Projection을 사용하는 이유

Projection은 Entity 전체를 조회하지 않고 상태 응답에 필요한 필드만 선택한다.

```text
Document ID
Document 상태
현재 Version 번호
현재 Version 상태
처리 중 Version 번호
처리 중 Version 상태
활성 Job 상태
```

개념적으로 다음과 같은 형태다.

```java
public record DocumentStatusProjection(
    Long documentId,
    DocumentStatus documentStatus,

    Integer currentVersionNo,
    DocumentVersionStatus currentVersionStatus,

    Integer processingVersionNo,
    DocumentVersionStatus processingVersionStatus,

    EmbeddingJobStatus jobStatus
) {
}
```

Projection을 사용하면 다음 장점이 있다.

```text
- 상태 응답에 필요하지 않은 컬럼을 조회하지 않는다.
- Entity 전체를 Controller에 노출하지 않는다.
- API 응답 구조와 DB Entity 구조의 결합을 줄인다.
- Lazy Loading으로 인한 추가 쿼리를 줄일 수 있다.
- 조회 결과가 Dirty Checking 대상이 되지 않는다.
```

JPA Entity는 영속성 컨텍스트의 관리 대상이므로 트랜잭션 안에서 상태를 변경하면 Dirty Checking에 의해 `UPDATE`가 실행될 수 있다.

```text
Entity 조회
→ 영속성 컨텍스트가 관리
→ Entity 필드 변경
→ 트랜잭션 종료
→ Dirty Checking
→ UPDATE 실행
```

반면 Projection은 조회 결과를 담는 DTO이므로 영속성 컨텍스트의 변경 감지 대상이 아니다.

이번 API는 조회 전용이므로 Document, Version, Job 상태를 변경하지 않는다.

### 5.3 단일 쿼리의 한계

하나의 쿼리는 여러 번 조회하면서 서로 다른 시점의 값이 섞이는 문제를 줄인다.

그러나 잘못된 DB 상태 자체를 자동으로 정상화하지는 않는다.

다음과 같은 상태가 존재하면 조회 결과가 없거나 여러 행으로 반환될 수 있다.

```text
처리 중 Version은 있지만 활성 Job이 없음
처리 중 Version 하나에 활성 Job이 두 개 존재
하나의 Document에 처리 중 Version이 두 개 존재
```

따라서 조회 결과의 행 개수와 상태 조합을 Service 또는 Converter에서 검증한다.

비정상 상태를 발견하면 `INDEXING_STATUS_INCONSISTENT` 오류를 반환한다.

---

## 6. 권한

상태 조회에는 기존 `PermissionQueryService.canReadDocument()`를 사용한다.

지원하는 문서 읽기 권한 유형은 다음과 같다.

```text
OWNER
PUBLIC
USER_CACHE
ROLE
DEPARTMENT
```

조회 순서는 다음과 같다.

```text
1. 사용자 인증 확인
2. 문서 읽기 권한 확인
3. 상태 Projection 조회
4. 상태 일관성 검증
5. 응답 DTO 변환
```

읽기 권한이 없으면 `PERMISSION_DENIED`를 반환하고 상태 Projection 조회를 실행하지 않는다.

```text
권한 없음
→ PERMISSION_DENIED
→ findDocumentStatus() 실행하지 않음
```

존재하지 않는 문서는 `DOCUMENT_NOT_FOUND`로 처리한다.

Soft delete된 문서도 외부에서는 존재하지 않는 문서로 취급한다.

```text
문서 없음
→ DOCUMENT_NOT_FOUND

문서 soft delete
→ DOCUMENT_NOT_FOUND
```

API는 기존 Security 설정의 `anyRequest().authenticated()` 적용을 받는다.

따라서 별도의 Security 경로 변경은 필요하지 않다.

---

## 7. 구현 구조

```text
DocumentQueryController
→ DocumentQueryService
  → PermissionQueryService.canReadDocument()
  → DocumentRepository.findDocumentStatus()
  → DocumentStatusConverter
→ DocumentStatusResponse
```

각 계층의 책임은 다음과 같다.

### DocumentQueryController

```text
- HTTP 요청 수신
- 인증 사용자 정보 전달
- DocumentStatusResponse 반환
```

### DocumentQueryService

```text
- 문서 읽기 권한 확인
- 상태 Projection 조회
- 조회 결과 개수 검증
- 상태 불일치 검증
- Converter 호출
```

### DocumentRepository

```text
- Document와 관련 상태를 하나의 Projection 쿼리로 조회
- Entity 전체가 아닌 필요한 필드만 반환
```

### DocumentStatusConverter

```text
- Projection을 외부 응답 DTO로 변환
- INDEXED 상태의 Version만 currentVersion으로 변환
- 처리 중 Version과 Job 상태를 processingVersion으로 변환
```

### DocumentStatusResponse

```text
DocumentStatusResponse
├─ CurrentVersionStatusResponse
└─ ProcessingVersionStatusResponse
```

응답 DTO는 현재 검색 가능한 버전과 처리 중인 버전의 필드 차이를 명확히 하기 위해 분리한다.

```java
public record DocumentStatusResponse(
    Long documentId,
    DocumentStatus documentStatus,
    CurrentVersionStatusResponse currentVersion,
    ProcessingVersionStatusResponse processingVersion
) {
}
```

```java
public record CurrentVersionStatusResponse(
    Integer versionNo,
    DocumentVersionStatus status
) {
}
```

```java
public record ProcessingVersionStatusResponse(
    Integer versionNo,
    DocumentVersionStatus status,
    EmbeddingJobStatus jobStatus
) {
}
```

`ProcessingVersionStatusResponse`만 `jobStatus`를 포함한다.

현재 검색 가능한 Version은 이미 처리가 완료된 상태이므로 활성 작업 상태를 외부에 제공할 필요가 없다.

외부 클라이언트가 내부 작업을 직접 조작하지 않으므로 다음 식별자는 이번 응답에 포함하지 않는다.

```text
DocumentVersion ID
EmbeddingJob ID
EmbeddingJobAttempt ID
Worker ID
```

---

## 8. 오류 응답

| 상황 | HTTP | 오류 코드 |
|---|---:|---|
| 인증되지 않은 요청 | 401 | `COMMON-007` |
| 문서 없음 또는 삭제된 문서 | 404 | `DOCUMENT-001` |
| 문서 읽기 권한 없음 | 403 | `ROLE-002` |
| Version과 Job 상태 불일치 | 500 | `DOCUMENT-STATUS-001` |

상태 불일치 오류는 외부 응답에 DB 상세 정보를 노출하지 않는다.

서버 오류 로그에는 문제 추적에 필요한 최소 정보만 남긴다.

```text
documentId
Projection 조회 행 개수
currentVersion 존재 여부
processingVersion 존재 여부
activeJob 존재 여부
```

Version ID, Job ID, 현재 상태 조합을 로그에 추가할 수 있지만 파일 내용이나 사용자 민감 정보는 기록하지 않는다.

---

## 9. 제외 범위와 후속 계약

이번 이슈에는 다음 기능이 포함되지 않는다.

- 인덱싱 완료 시 `current_version_id`를 교체하는 기능
- 실패한 Version과 Job 상태 전환
- 자동 재시도
- Worker Lock 만료 복구
- FAILED Version 수동 재처리
- 실패 Version 상세 조회
- Worker 실행
- 파서 실행
- 청커 실행
- 임베딩 서버 호출
- 벡터 저장 처리

후속 인덱싱 완료 기능은 다음 작업을 하나의 일관된 처리 단위로 수행해야 한다.

```text
1. 새 Version을 INDEXED로 변경
2. EmbeddingJob을 완료 상태로 변경
3. documents.current_version_id를 새 Version으로 교체
4. Document 상태를 INDEXED로 변경
```

완료 처리 이후 이 API는 변경된 DB 상태를 동일한 응답 계약으로 반환한다.

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

실패 상세 정보는 실패 및 재시도 기능에서 별도 필드나 별도 조회 API로 확장한다.

이번 API에는 실패 원인, 재시도 횟수, Attempt 이력을 포함하지 않는다.

---

## 10. 테스트

### 10.1 Repository 테스트

- 실제 OpenSQL 스키마에서 Projection 쿼리가 정상적으로 실행된다.
- Document와 `INDEXED` Current Version을 함께 조회한다.
- Processing Version과 Active Job을 함께 조회한다.
- Current Version이 없어도 Document를 조회한다.
- Processing Version이 없어도 Document를 조회한다.
- Active Job이 없어도 조회 결과를 반환하고 Service에서 불일치로 판정할 수 있다.
- Active Job이 중복되면 여러 조회 행이 반환된다.
- Processing Version이 중복되면 여러 조회 행이 반환된다.
- Soft delete된 문서는 조회되지 않는다.

### 10.2 Service 테스트

- 최초 Version 처리 중 `currentVersion=null`을 반환한다.
- Version 1이 `INDEXED`이면 `currentVersion`으로 반환한다.
- Version 1이 `INDEXED`이고 처리 중 Version이 없으면 `processingVersion=null`을 반환한다.
- Version 1을 검색 가능 상태로 유지하면서 Version 2의 처리 상태를 반환한다.
- `UPLOADED` Version과 `PENDING` Job 조합을 반환한다.
- `PARSING` Version과 `PROCESSING` Job 조합을 반환한다.
- `EMBEDDING` Version과 `PROCESSING` Job 조합을 반환한다.
- 처리 중 Version과 활성 Job 상태를 함께 반환한다.
- 처리 중 Version에 활성 Job이 없으면 상태 불일치 오류를 반환한다.
- 활성 Job이 중복되면 상태 불일치 오류를 반환한다.
- 처리 중 Version이 중복되면 상태 불일치 오류를 반환한다.
- 읽기 권한이 없으면 상태 Projection 조회를 실행하지 않는다.
- 문서가 없으면 `DOCUMENT_NOT_FOUND`를 반환한다.
- 삭제된 문서면 `DOCUMENT_NOT_FOUND`를 반환한다.

### 10.3 Converter 테스트

- `INDEXED` Version만 `CurrentVersionStatusResponse`로 변환한다.
- `INDEXED`가 아닌 Current Version은 `null`로 변환한다.
- 처리 중 Version과 Job을 `ProcessingVersionStatusResponse`로 변환한다.
- 처리 중 Version이 없으면 `processingVersion=null`로 변환한다.
- Version ID와 Job ID가 외부 응답에 포함되지 않는다.

### 10.4 Controller 테스트

- 인증된 사용자가 상태를 조회할 수 있다.
- 미인증 요청에 401을 반환한다.
- 읽기 권한이 없는 요청에 403을 반환한다.
- 존재하지 않는 문서에 404를 반환한다.
- 상태 불일치가 발생하면 500과 지정된 오류 코드를 반환한다.
- 응답 JSON 필드와 null 처리 계약을 검증한다.

### 10.5 전체 검증

- Repository 테스트가 통과한다.
- Service 테스트가 통과한다.
- Converter 테스트가 통과한다.
- Controller 테스트가 통과한다.
- 전체 애플리케이션 빌드가 통과한다.
- 실제 OpenSQL 환경에서 Projection 쿼리가 정상적으로 동작한다.

---

## 11. 완료 기준

- 읽기 권한이 있는 사용자가 문서 상태를 조회할 수 있다.
- 검색 가능한 `INDEXED` Version만 `currentVersion`으로 반환한다.
- 최초 Version이 처리 중이면 `currentVersion`을 `null`로 반환한다.
- 처리 중 Version과 활성 Job 상태를 하나의 조회 결과로 반환한다.
- Version 상태와 Job 상태의 역할이 명확히 구분된다.
- 새 Version이 처리되는 동안 기존 검색 가능 Version이 유지된다.
- 처리 완료 후 새 Version이 `currentVersion`으로 반환된다.
- 처리 중 Version이 없으면 `processingVersion`을 `null`로 반환한다.
- 처리 중 Version에 활성 Job이 없으면 상태 불일치 오류를 반환한다.
- 활성 Job 또는 처리 중 Version이 중복되면 상태 불일치 오류를 반환한다.
- 상태 불일치를 정상 응답으로 숨기지 않는다.
- 조회 과정에서 Document, DocumentVersion, EmbeddingJob 상태를 변경하지 않는다.
- Entity 전체를 Controller에 노출하지 않는다.
- Repository, Service, Converter, Controller 테스트가 통과한다.
- 실제 OpenSQL 스키마 검증과 전체 빌드가 통과한다.
