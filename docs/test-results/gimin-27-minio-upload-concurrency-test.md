# 이슈 #27 실제 MinIO 업로드 동시성 테스트 결과

## 1. 검증 목적

문서 업로드와 새 버전 업로드에 구현된 파일 중복 제거 및 후보 Object 보상 삭제가 실제 MinIO에서도 동작하는지 검증했다.

기존 통합 테스트는 실제 OpenSQL/PostgreSQL을 사용했지만 `FileStorageService`는 Mock이었다. 따라서 삭제 메서드가 호출됐다는 사실만 확인할 수 있었고, 경합 패자의 Object가 실제 MinIO에서 사라졌는지는 보장하지 못했다.

이번 테스트는 다음 조건을 실제 저장소에서 확인했다.

- 동일 파일 후보 Object 두 개가 실제 MinIO에 저장되는가?
- DB FileObject는 하나만 채택되는가?
- 경합에서 패한 후보 Object가 실제 삭제되는가?
- DB의 `object_key`와 MinIO에 남은 Object Key가 일치하는가?
- 남은 Object의 크기, Content-Type 및 SHA-256이 DB 값과 일치하는가?

## 2. 테스트 환경

| 구성요소 | 사용 환경 |
|---|---|
| 애플리케이션 | Spring Boot test context |
| DB | OpenSQL/PostgreSQL 14.6 |
| Schema | `docgrid_test` |
| Object Storage | 실제 MinIO Docker container |
| 테스트 profile | `test`, `minio-integration` |
| 테스트 bucket | 실행별 `docgrid-pr22-{uuid}` |
| 동시 실행 | 2개 Executor thread |

MinIO 인증값은 코드에 하드코딩하지 않고 `.env`의 환경변수에서 읽었다. 개발용 `docgrid` bucket과 데이터를 분리하기 위해 테스트 전용 bucket을 동적으로 주입했다.

## 3. 테스트 아키텍처

테스트는 Controller나 HTTP 인증 계층을 통과하지 않고 실제 Facade부터 실행한다.

```text
테스트 요청 thread
→ DocumentUploadFacade 또는 DocumentVersionUploadFacade
→ 파일 검증 및 SHA-256 계산
→ 실제 MinIOStorageService를 감싼 Barrier Wrapper
→ FileObjectResolutionService
→ 실제 OpenSQL/PostgreSQL transaction
→ 후보 채택 또는 보상 삭제
```

HTTP 대신 Facade를 호출한 이유는 이번 검증 대상이 인증이나 multipart 바인딩이 아니라 MinIO와 DB 사이의 경합 및 정합성이기 때문이다. Facade 아래의 파일 검증, 해시 계산, 실제 MinIO 저장, DB 트랜잭션, 후보 삭제는 모두 프로덕션 구현을 사용했다.

테스트 전용 Wrapper는 다음 역할만 담당했다.

- 실제 MinIO 저장 서비스에 저장과 삭제 위임
- 실제 저장이 끝난 후보 Object 기록
- 실제 삭제가 끝난 후보 Object 기록
- 저장 완료 직후 Barrier 대기

프로덕션 업로드 코드는 변경하지 않았다.

## 4. 경합 재현 방법

두 thread를 단순히 동시에 시작하면 요청 하나가 DB 저장까지 끝낸 뒤 다른 요청이 기존 FileObject를 발견할 수 있다. 이 경우 후보 Object가 하나만 생성돼 실제 INSERT 경합을 검증하지 못한다.

이번 테스트에서는 Barrier를 실제 MinIO 저장 이후에 배치했다.

```text
요청 A: FileObject 사전 조회 → 없음
요청 B: FileObject 사전 조회 → 없음

요청 A: MinIO 후보 A 실제 저장 완료 → Barrier 대기
요청 B: MinIO 후보 B 실제 저장 완료 → Barrier 대기

후보 2개 저장 보장
→ Barrier 동시 해제
→ DB 경합 시작
```

Barrier와 Future에는 각각 timeout을 적용해 한 thread가 실패해도 테스트가 무한 대기하지 않도록 했다.

## 5. 시나리오 1: 동일 파일로 새 Document 동시 생성

### 요청

논리적으로 다음 새 문서 요청 두 건을 동시에 실행했다.

```http
POST /api/documents
Content-Type: multipart/form-data
```

두 요청의 조건:

| 항목 | 요청 A | 요청 B |
|---|---|---|
| 사용자 | 동일 | 동일 |
| 파일 바이트 | 동일 | 동일 |
| 파일 크기 | 동일 | 동일 |
| 제목 | 서로 다름 | 서로 다름 |
| visibility | PRIVATE | PRIVATE |

두 요청은 서로 다른 논리 Document를 만드는 요청이므로 모두 성공해야 하지만 실제 파일은 하나만 남아야 한다.

### DB 검증에 사용한 쿼리

파일 해시와 크기에 해당하는 FileObject 수를 확인했다.

```sql
SELECT COUNT(*)
FROM file_objects
WHERE file_hash = :fileHash
  AND file_size = :fileSize;
```

응답으로 받은 ID만 이용해 Document 수를 확인했다.

```sql
SELECT COUNT(*)
FROM documents
WHERE id IN (:firstDocumentId, :secondDocumentId);
```

DocumentVersion 수를 확인했다.

```sql
SELECT COUNT(*)
FROM document_versions
WHERE document_id IN (:firstDocumentId, :secondDocumentId);
```

EmbeddingJob 수를 확인했다.

```sql
SELECT COUNT(*)
FROM embedding_jobs
WHERE document_version_id IN (
    SELECT id
    FROM document_versions
    WHERE document_id IN (:firstDocumentId, :secondDocumentId)
);
```

DB에서 최종 채택된 MinIO Key를 조회했다.

```sql
SELECT object_key
FROM file_objects
WHERE file_hash = :fileHash
  AND file_size = :fileSize;
```

### 실제 첫 실행 결과

응답 및 DB 결과:

```text
Document IDs: 56, 57
DocumentVersion IDs: 65, 66
FileObject ID: 58
두 요청 성공: 2건
FileObject: 1건
Document: 2건
DocumentVersion: 2건
EmbeddingJob: 2건
```

실제 MinIO 후보:

```text
후보 A
documents/bf16244d-36e5-47be-9889-39dcd807598c/
5c49ab23-5702-481d-9c83-029c6a89d4d5.txt

후보 B
documents/aee7d1bb-8595-4c5c-bb66-09c739567b23/
d8c36e1e-68c1-4a6b-883b-283951a943d9.txt
```

경합 결과:

```text
삭제된 패자 후보: 후보 A
DB에 채택된 후보: 후보 B
MinIO 최종 잔존: 후보 B 한 개
```

최종 잔존 Object Key는 DB `file_objects.object_key`와 정확히 일치했다.

Object를 실제로 조회해 다음 값도 검증했다.

- Object size와 업로드 파일 크기 일치
- Content-Type `text/plain` 일치
- Object를 다시 읽어 계산한 SHA-256과 DB `file_hash` 일치

### 분석

두 요청은 Barrier 이전에 모두 MinIO 저장을 완료했기 때문에 후보 Object가 실제로 두 개 생성됐다.

Barrier 해제 후 두 요청이 같은 `file_hash + file_size`로 FileObject INSERT를 경쟁했다. DB 유니크 제약과 `ON CONFLICT DO NOTHING`에 의해 한 후보만 FileObject로 채택됐다.

경합에서 패한 요청은 승자의 FileObject를 다시 조회해 자신의 DocumentVersion에 연결했다. 두 Document는 모두 정상 생성됐지만 패자 요청의 MinIO 후보는 DB에 채택되지 않았으므로 Facade에서 실제 삭제됐다.

따라서 최종 상태는 다음과 같다.

```text
논리 Document 2개
DocumentVersion 2개
EmbeddingJob 2개
FileObject 1개
MinIO Object 1개
```

## 6. 시나리오 2: 동일 Document에 같은 수정 파일 동시 업로드

### 초기 문서 준비

먼저 실제 MinIO를 이용해 초기 파일 A를 업로드했다.

```http
POST /api/documents
Content-Type: multipart/form-data
```

현재 Worker와 인덱싱 완료 API가 없으므로 테스트에서 다음 쿼리로 초기 Version의 완료 상태를 모의했다.

```sql
UPDATE document_versions
SET status = 'INDEXED',
    indexed_at = CURRENT_TIMESTAMP
WHERE id = :initialVersionId;

UPDATE embedding_jobs
SET status = 'INDEXED',
    completed_at = CURRENT_TIMESTAMP
WHERE id = :initialJobId;

UPDATE documents
SET status = 'INDEXED',
    current_version_id = :initialVersionId
WHERE id = :documentId;
```

이 변경은 새 버전 API의 사전 조건을 만들기 위한 테스트 전용 처리다.

초기 파일 A의 저장 기록이 경합 후보 수에 포함되지 않도록 Wrapper 관측값을 초기화한 뒤, 동일한 수정 파일 B로 다음 요청 두 건을 동시에 실행했다.

```http
POST /api/documents/{documentId}/versions
Content-Type: multipart/form-data
```

### 예상 동작

두 요청 모두 사전 검증 시점에는 처리 중 Version이 없으므로 MinIO 후보를 저장한다.

그 이후에는 Document 행 잠금에 의해 다음과 같이 처리돼야 한다.

```text
승자 요청
→ Document Lock
→ Version 2 생성
→ PENDING Job 생성
→ DB Commit
→ 후보 유지

패자 요청
→ Document Lock 대기
→ Commit 이후 재검증
→ 처리 중 Version 발견
→ DOCUMENT_VERSION_IN_PROGRESS
→ 자신의 후보 삭제
```

### DB 검증에 사용한 쿼리

문서의 전체 Version 수를 확인했다.

```sql
SELECT COUNT(*)
FROM document_versions
WHERE document_id = :documentId;
```

문서에 연결된 Job 수를 확인했다.

```sql
SELECT COUNT(*)
FROM embedding_jobs
WHERE document_version_id IN (
    SELECT id
    FROM document_versions
    WHERE document_id = :documentId
);
```

처리 중 Version 수를 확인했다.

```sql
SELECT COUNT(*)
FROM document_versions
WHERE document_id = :documentId
  AND status IN ('UPLOADED', 'PARSING', 'CHUNKED', 'EMBEDDING');
```

기존 current Version 유지 여부를 확인했다.

```sql
SELECT current_version_id
FROM documents
WHERE id = :documentId;
```

수정 파일 B의 FileObject와 Object Key를 확인했다.

```sql
SELECT id, object_key
FROM file_objects
WHERE file_hash = :changedFileHash
  AND file_size = :changedFileSize;
```

### 실제 첫 실행 결과

요청 및 DB 결과:

```text
Document ID: 58
기존 current Version ID: 67
새 Version ID: 68
성공 요청: 1건
실패 요청: 1건
실패 코드: DOCUMENT_VERSION_IN_PROGRESS
DocumentVersion: 총 2건
EmbeddingJob: 총 2건
처리 중 Version: 1건
current_version_id: 67 유지
```

수정 파일 B의 실제 MinIO 후보:

```text
후보 A
documents/2a2a3582-9eb6-4268-acd2-33698ad97da9/
692972e1-3395-49f3-9915-e4b946f42ade.txt

후보 B
documents/ceb366b7-a6c4-4855-a6bf-8880265aa0c9/
415e8bd7-2d5f-4f8f-9a81-5fd28f039f4e.txt
```

경합 결과:

```text
DB에 채택된 수정 파일 후보: 후보 A
실패 요청에서 삭제한 후보: 후보 B
기존 Version 1 Object: 유지
MinIO 최종 Object: 초기 파일 A + 수정 파일 B, 총 2개
```

### 분석

신규 Document 동시 업로드와 달리 같은 문서의 Version 요청 두 개가 모두 성공해서는 안 된다.

첫 요청이 Version 2를 만든 뒤에도 `current_version_id`는 기존 INDEXED Version 1을 유지했다. 두 번째 요청은 Document Lock을 획득한 후 상태를 다시 읽었기 때문에 첫 요청이 생성한 UPLOADED Version을 발견했다.

그 결과 두 번째 요청은 `DOCUMENT_VERSION_IN_PROGRESS`로 실패했다. 이 실패는 MinIO 후보 저장 이후 발생했으므로 Version Facade가 해당 요청의 후보 Object를 실제 삭제했다.

최종적으로 다음 상태가 확인됐다.

```text
기존 Document 1개
Version 1 INDEXED
Version 2 UPLOADED
새 Job 1개 PENDING
current_version_id는 Version 1 유지
수정 파일 후보는 실제 MinIO에 1개만 잔존
```

## 7. Version Facade 보상 분기 단위 테스트

새 버전 업로드 Facade의 보상 경계도 6개 단위 테스트로 고정했다.

| 상황 | 검증 결과 |
|---|---|
| 기존 FileObject 발견 | MinIO 저장·삭제 없이 재사용 |
| 후보 저장 후 DB 실패 | 후보 삭제 후 원래 DB 오류 유지 |
| 후보가 DB에 미채택 | 해당 후보 삭제 |
| DB 실패와 후보 삭제 동시 실패 | 원래 DB 오류 유지 |
| DB 성공 후 미사용 후보 삭제 실패 | 성공 응답 유지 |
| MinIO 저장 실패 | DB Version 저장 미호출 |

특히 DB Commit 이후 후보 삭제 실패를 API 실패로 바꾸지 않는 정책을 확인했다. DB는 이미 성공한 상태이므로 실패 응답을 반환하면 클라이언트 재시도로 논리 Version이나 Document가 중복될 수 있기 때문이다.

## 8. 반복 실행 결과

기본 전체 테스트를 먼저 실행한 후 실제 MinIO 동시성 테스트를 5회 반복했다.

| 실행 | 신규 문서 경합 | 새 버전 경합 |
|---:|---|---|
| 최초 상세 실행 | 성공 | 성공 |
| 반복 1 | 성공 | 성공 |
| 반복 2 | 성공 | 성공 |
| 반복 3 | 성공 | 성공 |
| 반복 4 | 성공 | 성공 |
| 반복 5 | 성공 | 성공 |
| 최종 전체 검증 | 성공 | 성공 |

총 결과:

```text
기본 ./gradlew test: BUILD SUCCESSFUL
실제 MinIO task: 7회 연속 BUILD SUCCESSFUL
MinIO 동시성 테스트: 14/14 성공
timeout: 0건
간헐 실패: 0건
```

전용 테스트 XML 결과에서도 다음 상태를 확인했다.

```text
tests=2
skipped=0
failures=0
errors=0
```

## 9. 테스트 데이터 정리 확인

각 테스트가 끝난 뒤 다음 순서로 DB를 정리했다.

```text
EmbeddingJob 삭제
→ documents.current_version_id 해제
→ DocumentVersion 삭제
→ Document 삭제
→ 참조되지 않는 테스트 FileObject 삭제
```

FileObject 정리에는 테스트 전용 bucket만 사용했다.

```sql
DELETE FROM file_objects
WHERE bucket_name = :testBucket
  AND NOT EXISTS (
      SELECT 1
      FROM document_versions
      WHERE file_object_id = file_objects.id
  );
```

MinIO는 다음 순서로 정리했다.

```text
테스트 bucket Object 전체 조회
→ Object 개별 삭제
→ 테스트 bucket 삭제
```

반복 테스트 이후 실제 확인 쿼리:

```sql
SELECT COUNT(*)
FROM docgrid_test.file_objects
WHERE bucket_name LIKE 'docgrid-pr22-%';
```

결과:

```text
0
```

테스트 문서 잔존 여부도 확인했다.

```sql
SELECT COUNT(*)
FROM docgrid_test.documents
WHERE title LIKE '동시 신규 문서 %'
   OR title LIKE '버전 경합 문서 %';
```

결과:

```text
0
```

MinIO bucket 목록에서도 `docgrid-pr22-*` bucket이 남아 있지 않았다.

## 10. 최종 결론

이번 테스트로 다음 사실을 실제 OpenSQL과 MinIO 환경에서 확인했다.

- DB 유니크 제약은 동일 FileObject row를 하나로 제한한다.
- `ON CONFLICT DO NOTHING` 결과로 현재 후보의 채택 여부를 구분할 수 있다.
- 신규 Document 요청은 둘 다 성공하면서 하나의 FileObject와 MinIO Object를 공유한다.
- 동일 Document의 새 Version 경합에서는 Document Lock과 트랜잭션 내부 재검증으로 하나만 성공한다.
- 경합 패자의 MinIO 후보 Object는 실제로 삭제된다.
- 기존 current Version Object와 공유 Object는 삭제되지 않는다.
- DB의 Object Key와 MinIO에 실제로 남은 Object Key가 일치한다.
- 남은 Object의 크기, Content-Type, SHA-256도 DB 값과 일치한다.
- 기본 테스트는 MinIO 의존 없이 실행되고 실제 MinIO 검증은 전용 task로 분리된다.

Mock에서 `delete()` 호출을 검증한 것과 실제 Object가 존재하지 않는 것을 검증한 것은 서로 다른 보장이다. 이번 작업에서는 후자의 보장까지 자동화했다.

## 11. 남아 있는 한계

이번 테스트는 정상적으로 실행 중인 애플리케이션이 경합 패자의 후보를 보상 삭제하는 경로를 검증한다.

다음 상황은 아직 해결하지 않는다.

- MinIO 저장 직후 프로세스 강제 종료
- DB Commit 전후 애플리케이션 비정상 종료
- 장시간 MinIO 삭제 장애
- 네트워크 단절로 삭제 성공 여부를 알 수 없는 상황
- 운영 DB와 MinIO 사이의 기존 고아 Object 탐지

이 문제는 요청 단위 보상 삭제만으로 해결할 수 없다. 후속 범위에서는 일정 유예 시간 이후 DB가 참조하지 않는 MinIO Object를 탐지하고 삭제하는 Reconciliation 작업이 필요하다.
