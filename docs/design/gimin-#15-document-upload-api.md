# Issue #15 문서 업로드 접수 API 설계 및 구현

## 1. 목적

사용자가 TXT 또는 Markdown 파일을 업로드하면 원본을 MinIO에 저장하고 인덱싱 작업을 접수한다.

```http
POST /api/documents
Content-Type: multipart/form-data
Authorization: Bearer {token}
```

요청 한 번으로 다음 데이터가 생성된다.

```text
FileObject
Document
DocumentVersion 1
PENDING EmbeddingJob
```

파싱, 청킹, 임베딩 실행과 Vector 저장은 포함하지 않는다.

## 2. 요청과 인증

| 필드 | 설명 |
|---|---|
| `file` | 업로드할 TXT 또는 Markdown 파일 |
| `title` | 논리 문서 제목 |
| `description` | 문서 설명 |
| `visibility` | `PUBLIC` 또는 `PRIVATE` |

사용자 ID는 요청값으로 받지 않고 인증된 `@CurrentUser`에서 가져온다.

## 3. 파일 검증

MinIO에 저장하기 전에 다음 항목을 검증한다.

- 빈 파일 여부
- 최대 파일 크기
- 허용 확장자 `.txt`, `.md`
- 확장자와 Content-Type 조합
- 빈 파일명과 최대 길이
- Path Traversal이 포함된 파일명

검증을 통과한 파일은 전체 바이트로 SHA-256을 계산한다. 파일 크기만 같다고 동일 파일로 판단하지 않는다.

## 4. 논리 문서와 실제 파일 분리

```text
Document
→ 사용자가 관리하는 논리 문서

DocumentVersion
→ 특정 시점의 문서 내용

FileObject
→ MinIO Object의 위치와 해시·크기를 보관하는 메타데이터
```

동일한 파일을 여러 번 새 문서로 올릴 수 있으므로 Document는 요청마다 생성하지만, 실제 파일은 `file_hash + file_size`가 같으면 기존 `FileObject`를 재사용한다.

## 5. 업로드 처리 흐름

```text
파일 검증과 SHA-256 계산
→ 기존 FileObject 사전 조회
→ 있으면 MinIO 저장 없이 재사용
→ 없으면 UUID 기반 Object Key로 MinIO 후보 저장
→ DB 트랜잭션에서 FileObject 결정
→ Document와 Version 1 생성
→ 기본 EmbeddingModel을 연결한 PENDING Job 생성
→ 첫 Version을 current_version_id로 설정
```

Object Key는 원본 파일명을 직접 사용하지 않고 UUID를 조합한다.

```text
documents/{directoryUuid}/{objectUuid}.{extension}
```

## 6. 동일 파일 동시 업로드

사전 조회는 불필요한 저장을 줄이는 최적화일 뿐 동시성 제어가 아니다. 두 요청이 동시에 조회하면 둘 다 FileObject가 없다고 판단할 수 있다.

DB에는 다음 제약을 둔다.

```sql
UNIQUE (file_hash, file_size)
```

그리고 `INSERT ... ON CONFLICT DO NOTHING`의 영향 행 수로 현재 후보가 채택됐는지 판단한다.

```text
inserted = 1
→ 현재 후보 채택

inserted = 0
→ 다른 요청이 만든 FileObject 재조회·재사용
→ 현재 요청의 미사용 MinIO 후보 삭제
```

따라서 서로 다른 논리 Document 요청은 모두 성공하면서 실제 파일은 하나만 공유할 수 있다.

## 7. 트랜잭션과 보상 처리

MinIO는 PostgreSQL 트랜잭션에 참여하지 않는다. MinIO 저장 후 DB 작업이 실패하면 DB만 Rollback되고 Object는 남을 수 있다.

Facade는 외부 저장소 경계를 관리하고, Command Service는 DB 원자성을 관리한다.

```text
Facade
→ 파일 검증, 해시, MinIO 후보 저장, 실패·미채택 후보 삭제

DocumentUploadService
→ FileObject, Document, DocumentVersion, EmbeddingJob을 하나의 DB 트랜잭션으로 저장
```

DB 실패 시에는 현재 요청이 새로 저장한 후보만 삭제한다. 이미 다른 Version이 공유하는 기존 Object는 삭제하지 않는다.

## 8. 생성 상태

```text
Document.status = UPLOADED
DocumentVersion.status = UPLOADED
DocumentVersion.version_no = 1
Document.current_version_id = Version 1
EmbeddingJob.status = PENDING
```

EmbeddingJob에는 접수 시점의 활성 EmbeddingModel ID를 저장한다.

## 9. 검증 범위

- 정상 TXT·Markdown 업로드
- 잘못된 파일 요청 거절
- 같은 파일 순차 업로드 시 FileObject 재사용
- 같은 파일 동시 업로드 시 FileObject 한 건 유지
- MinIO 실패 시 DB 작업 미실행
- DB 실패 시 신규 후보 Object 보상 삭제
- 활성 EmbeddingModel이 없을 때 DB 전체 Rollback
- Controller, 검증, 해시, Facade, Command Service 단위·통합 테스트

실제 MinIO에서 경합 패자의 Object가 삭제되는지는 [Issue #27 테스트 결과](./test-results/gimin-#27-minio-concurrency-integration-test.md)에서 추가로 검증한다.
