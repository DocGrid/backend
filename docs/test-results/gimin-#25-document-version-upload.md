# Issue #25 문서 새 버전 업로드 테스트 결과

## 1. 테스트 목적

`POST /api/documents/{documentId}/versions`가 다음 계약을 지키는지 Swagger, PostgreSQL, MinIO에서 확인했다.

- 변경된 파일은 기존 Document의 다음 Version으로 등록한다.
- 현재 Version과 동일한 파일은 중복 Version으로 저장하지 않는다.
- 동일 파일을 새 문서 API로 업로드하면 Document는 새로 만들고 FileObject는 재사용한다.
- 과거 Version의 파일로 되돌릴 때 새 Version을 만들고 기존 FileObject를 재사용한다.
- 새 Version이 INDEXED되기 전까지 기존 `current_version_id`를 유지한다.
- 파일명이 달라도 파일 바이트가 같으면 동일 파일로 판정한다.
- 파일 크기가 같아도 SHA-256이 다르면 다른 파일로 판정한다.

상세 설계는 [Issue #25 문서 새 버전 업로드 설계](../gimin-#25-document-version-upload.md)를 참고한다.

## 2. 테스트 환경과 제약

- Spring Boot local profile
- PostgreSQL 호환 OpenSQL-PG `app.public`
- MinIO `docgrid` bucket
- Swagger UI를 통한 API 호출
- `./gradlew test` 전체 테스트 수행

이슈 #25는 Version과 PENDING EmbeddingJob을 접수하는 범위까지만 담당한다. 실제 Worker와 인덱싱 완료 처리는 아직 구현되지 않았으므로, 연속 Version 테스트에서는 기존 Version과 Job을 DB에서 `INDEXED`로 변경해 완료 상태를 모의했다.

이 수동 변경은 테스트 전용이며 운영 흐름에서는 후속 인덱싱 완료 API가 담당한다.

## 3. 테스트 데이터

최초 파일 A:

```markdown
# 휴가 규정
연차는 15일입니다.
```

수정 파일 B:

```markdown
# 휴가 규정
연차는 20일입니다.
```

두 파일은 모두 42바이트지만 SHA-256은 서로 달랐다.

| 파일 | 크기 | SHA-256 |
|---|---:|---|
| A | 42 bytes | `53c7c5f76a970024a1ae9adeaa74a6b34065ca24764fbcb1c614c370b00e0430` |
| B | 42 bytes | `585d54f88dffa3a0212aa856332a2138a611cb8fb604208e31e5b27b9c922aed` |

따라서 파일 크기가 같아도 실제 내용이 다르면 다른 FileObject로 처리되어야 한다.

## 4. 시나리오별 결과

### 4.1 최초 문서 업로드

```http
POST /api/documents
```

파일 A를 `test1.md`로 업로드한 결과:

| 항목 | 결과 |
|---|---|
| Document ID | `5` |
| Version ID / No | `5` / `1` |
| FileObject ID | `4` |
| EmbeddingJob ID | `5` |
| Document / Version 상태 | `UPLOADED` / `UPLOADED` |
| Job 상태 | `PENDING` |
| original_filename | `test1.md` |
| content_type | `text/markdown` |

`documents.current_version_id`는 최초 Version ID인 `5`로 설정됐고, MinIO에는 파일 A Object 하나가 생성됐다.

### 4.2 같은 크기의 수정 파일을 Version 2로 업로드

Version 1의 완료 상태를 모의한 뒤 파일 B를 업로드했다.

```http
POST /api/documents/5/versions
```

응답:

```json
{
  "documentId": 5,
  "documentVersionId": 6,
  "versionNo": 2,
  "embeddingJobId": 6,
  "currentVersionId": 5,
  "documentStatus": "INDEXED",
  "versionStatus": "UPLOADED",
  "jobStatus": "PENDING"
}
```

검증 결과:

- 기존 Document ID `5`를 재사용했다.
- Version No가 `1`에서 `2`로 증가했다.
- 파일 크기는 같지만 해시가 달라 FileObject `5`와 새로운 MinIO Object가 생성됐다.
- Version 2 전용 PENDING Job `6`이 생성됐다.
- Version 2가 아직 처리 중이므로 `current_version_id`는 Version 1인 `5`를 유지했다.

### 4.3 파일명이 다른 동일 파일을 현재 문서의 Version으로 업로드

`test2.md`의 내용을 현재 Version과 동일하게 만든 뒤 Version API를 호출했다.

```text
test1.md: hash B, 42 bytes
test2.md: hash B, 42 bytes
```

결과:

```json
{
  "status": 409,
  "code": "DOCUMENT-VERSION-001",
  "message": "현재 버전과 동일한 파일입니다.",
  "method": "POST",
  "path": "/api/documents/5/versions",
  "success": false
}
```

오류 이후에도 다음 개수는 변하지 않았다.

```text
DocumentVersion: 2개
EmbeddingJob: 2개
FileObject: 추가 없음
MinIO Object: 추가 없음
```

파일명이 아니라 `file_hash + file_size`로 동일 파일을 판정하고, MinIO 저장 전에 중복 요청을 차단하는 것을 확인했다.

### 4.4 동일 파일을 새로운 논리 문서로 업로드

같은 파일 B를 새 문서 API로 업로드했다.

```http
POST /api/documents
```

결과:

| 항목 | 기존 문서 Version 2 | 새 문서 Version 1 |
|---|---:|---:|
| Document ID | `5` | `6` |
| Version ID | `6` | `7` |
| FileObject ID | `5` | `5` |
| original_filename | `test1.md` | `test2.md` |

논리 Document와 Version, EmbeddingJob은 새로 생성됐지만 실제 바이너리는 기존 FileObject `5`를 재사용했다. MinIO Object도 추가되지 않았다.

또한 FileObject를 공유하면서도 각 Version의 `original_filename`이 별도로 보존되는 것을 확인했다.

### 4.5 과거 Version 파일로 되돌리기

현재 Version이 파일 B인 상태에서 과거 Version 1과 같은 파일 A를 `revert.md`로 업로드했다.

결과:

```json
{
  "documentId": 5,
  "documentVersionId": 8,
  "versionNo": 3,
  "embeddingJobId": 8,
  "currentVersionId": 6,
  "documentStatus": "INDEXED",
  "versionStatus": "UPLOADED",
  "jobStatus": "PENDING"
}
```

DB 연결 결과:

| Version | original_filename | FileObject |
|---:|---|---:|
| 1 | `test1.md` | `4` |
| 2 | `test1.md` | `5` |
| 3 | `revert.md` | `4` |

Version 3과 EmbeddingJob은 새로 생성됐지만 파일 A의 기존 FileObject `4`를 재사용했다. 새 Version이 아직 INDEXED되지 않았으므로 `current_version_id`는 Version 2인 `6`을 유지했다.

## 5. MinIO 검증 결과

테스트에서 생성한 논리 데이터는 다음과 같다.

```text
Documents: 2
DocumentVersions: 4
FileObjects: 2
MinIO Objects: 2
```

MinIO에는 실제로 서로 다른 내용 A와 B에 해당하는 Object만 존재했다.

```text
42B documents/{uuid}/{uuid}.md
42B documents/{uuid}/{uuid}.md
```

DB 기준 참조 수:

| FileObject | 내용 | 참조 Version 수 |
|---:|---|---:|
| `4` | 파일 A | 2 |
| `5` | 파일 B | 2 |

`mc stat`으로 Object 크기와 Content-Type을 확인하고, `mc cat`으로 파일 B의 실제 내용이 다음과 같은 것도 확인했다.

```markdown
# 휴가 규정
연차는 20일입니다.
```

## 6. 자동 테스트 결과

실행 명령:

```bash
./gradlew test
```

결과:

```text
BUILD SUCCESSFUL
```

추가한 통합 테스트에서는 다음 동작을 자동 검증한다.

- 같은 크기지만 내용이 다른 파일의 Version 생성
- 새 Version 접수 후 기존 `current_version_id` 유지
- 현재 Version과 동일한 파일의 사전 거절
- 동일 파일 거절 시 저장소 업로드 미호출
- 과거 Version 파일로 되돌릴 때 기존 FileObject 재사용
- 신규/후속 Version 파일 메타데이터 저장

## 7. 최종 결론

다음 계약이 Swagger, DB, MinIO 및 자동 테스트에서 확인됐다.

- 문서의 동일성은 파일명이나 해시가 아니라 `documentId`와 호출 API로 결정한다.
- 파일의 동일성은 전체 바이트의 SHA-256과 파일 크기로 결정한다.
- 변경 파일은 기존 Document의 다음 Version으로 저장된다.
- 현재 Version과 동일한 파일은 아무 데이터도 생성하지 않고 `409`로 거절된다.
- 동일 파일을 새 문서로 업로드하면 Document는 새로 생성하고 FileObject는 재사용한다.
- 과거 Version의 내용으로 되돌릴 수 있으며 기존 FileObject를 재사용한다.
- 새 Version 처리 중에는 기존 검색 가능한 `current_version_id`를 유지한다.
- 여러 Version이 같은 FileObject를 공유해도 업로드 파일명은 Version별로 보존된다.
