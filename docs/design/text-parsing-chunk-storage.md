# 텍스트 파싱 및 Chunk 저장 설계

## 1. 목표

현재 Worker가 소유한 `PROCESSING` Embedding Job의 원본 TXT 또는 Markdown 파일을 MinIO에서 읽고,
결정적인 고정 크기 Chunk로 나누어 `document_chunks`에 원자적으로 저장한다.

이 기능의 운영 결과는 다음과 같다.

- 선행 단계에서 발급된 Job ID, Attempt ID, Worker ID, Claim Token을 하나의 실행 단위로 유지한다.
- Job이 직접 가리키는 `DocumentVersion`과 `FileObject`만 처리한다.
- 원본 파일을 UTF-8 텍스트로 엄격하게 해석한다.
- 같은 정규화 텍스트와 같은 Chunk 설정은 항상 같은 인덱스, 범위, 본문, Hash를 만든다.
- MinIO 읽기와 파싱 동안 DB Transaction과 행 잠금을 유지하지 않는다.
- Chunk 전체, Version의 `CHUNKED` 상태, `CHUNKED` 이벤트는 하나의 짧은 Transaction에서 함께
  Commit된다.
- 처리 전후에 현재 Claim 소유권과 Lease를 검증하여 오래된 Worker의 저장을 차단한다.
- 응답 유실이나 동시 재호출이 새 Chunk를 중복 생성하지 않고 기존 결과로 수렴한다.

성공 기준은 정상 TXT·Markdown 처리뿐 아니라 잘못된 Attempt, 오래된 Claim Token, 만료 Lease,
MinIO 장애, 잘못된 문자 인코딩, Chunk 저장 실패, 동시 중복 호출이 상태와 데이터를 오염시키지 않는
것이다.

## 2. 비범위

- PDF, DOCX, HTML 및 바이너리 문서 파싱
- Markdown 문법 제거, AST 구성, Heading 기반 의미 Chunking
- PDF 페이지 번호와 Markdown Section 제목 추출
- 문장·문단·토큰 기반 의미 Chunking
- 모델별 실제 Tokenizer를 이용한 정확한 Token 수 계산
- Chunk 단위 Streaming Insert와 대용량 Batch 최적화
- Embedding 생성 및 `embeddings` 저장
- `DocumentVersion`의 `EMBEDDING`, `INDEXED`, `FAILED` 전환
- `EmbeddingJob`의 완료·실패·재시도 상태 전환
- `EmbeddingJobAttempt`의 `SUCCESS`, `FAILED`, `TIMED_OUT`, `ABANDONED` 전환
- `PARSE_FAILED` 이벤트와 오류 상세 이력
- Lease 연장, 만료 Job 회수, Worker 자동 Polling
- 기존 Version의 Chunk 삭제·수정·재생성
- 일반 사용자용 문서 API에 Chunk 본문 또는 내부 실행 정보를 노출하는 기능
- MinIO 연결 설정, Bucket 생성 정책, Secret 관리 방식 변경
- 새 Message Broker, Cache, Metric Library, Markdown Library 도입

## 3. 현재 기준선

### 3.1 설계 기준

- 기준 브랜치: `develop`
- 구현 전 동기화한 `develop` Commit: `50da23e5a16d2c2987d8317646d9b0d54b4e7fcd`
- 구현 이슈와 Branch: GitHub Issue `#68`, `feature/68`
- 저장소: `https://github.com/DocGrid/backend.git`
- Framework: Spring Boot 3.5.16
- Language: Java 17
- DB: PostgreSQL/OpenSQL과 Flyway
- Object Storage: MinIO Java SDK

구현 전 최신 `develop`을 `feature/68`에 Merge해 RAG 저장·Ollama 변경을 함께 반영했다.
Attempt 시작과 Claim Token 계약, `EmbeddingJobRepository.findByIdForUpdate`,
`EmbeddingJobAttemptRepository`, 관리자용 Attempt 시작 API는 기준선에 포함돼 있다.
이번 구현은 공통 `EmbeddingJobOwnershipValidator`를 추가하고 Attempt 시작과 Chunk 저장 흐름이
같은 Worker·Claim Token·Lease 정책을 사용하도록 정리했다.

### 3.2 이미 존재하는 Domain과 Schema

| 구성요소 | 현재 계약 | 설계에서의 사용 |
| --- | --- | --- |
| `EmbeddingJob` | 대상 Version, 모델, 상태, 현재 Worker, Claim Token, Lease 보존 | 처리 대상과 현재 소유권의 기준 |
| `EmbeddingJobAttempt` | Job·Worker·Claim Token·Attempt 번호·상태·시작 시각 보존 | 이번 실행 세대 식별 |
| `DocumentVersion` | `UPLOADED`, `PARSING`, `CHUNKED` 상태와 FileObject 연관 보유 | 파싱 상태 전환 대상 |
| `Document` | 논리 문서와 파일 형식 보유 | TXT·Markdown Parser 선택 기준 |
| `FileObject` | Bucket, Object Key, Content Type, 크기, 파일 Hash 보유 | MinIO 읽기 위치 |
| `DocumentChunk` | Version별 Chunk 본문, 범위, Hash, Metadata 필드 보유 | 최종 저장 Entity |
| `IndexingEvent` | Job에 종속된 append-only 이벤트와 상태 Snapshot 보유 | 파싱 시작·Chunk 완료 기록 |
| `document_chunks` | Version·Chunk Index 유일성 제약 보유 | 중복 저장의 DB 최종 방어선 |

`V15__create_document_chunks.sql`과 `DocumentChunk`는 이미 필요한 컬럼을 모두 가진다.
새 테이블이나 Chunk 컬럼을 만들지 않는다.

현재 Schema의 중요한 제약은 다음과 같다.

- `(document_version_id, chunk_index)`는 유일하다.
- `chunk_text`, `token_count`, `char_start`, `char_end`는 필수다.
- `page_no`, `section_title`, `content_hash`, `metadata_json`은 nullable이다.
- `DocumentVersion.fileObject`는 DB와 Entity에서 nullable이지만, 업로드 기반 파싱 대상에는 반드시
  존재해야 하는 Application 불변식이다.
- 한 문서에서 `UPLOADED`, `PARSING`, `CHUNKED`, `EMBEDDING` Version은 한 건만 존재하도록
  `V30__add_document_version_upload_metadata.sql`의 Partial Unique Index가 방어한다.

### 3.3 이미 존재하는 상태와 이벤트

`DocumentVersionStatus`에는 `UPLOADED`, `PARSING`, `CHUNKED`, `EMBEDDING`, `INDEXED`, `FAILED`가
이미 존재한다. `DocumentVersion`에는 `markParsing`과 `markChunked`도 이미 있다.

`IndexingEventType`에는 `PARSE_STARTED`, `PARSE_FAILED`, `CHUNKED`가 이미 존재하고,
`IndexingEventRepository`도 있다. 따라서 새 Enum 값이나 이벤트 테이블 변경은 필요하지 않다.

정상 처리 중 상태 변화는 Version에만 적용한다.

| 대상 | 처리 전 | 파싱 시작 후 | Chunk 저장 후 |
| --- | --- | --- | --- |
| `EmbeddingJob` | `PROCESSING` | `PROCESSING` | `PROCESSING` |
| `EmbeddingJobAttempt` | `STARTED` | `STARTED` | `STARTED` |
| `DocumentVersion` | `UPLOADED` | `PARSING` | `CHUNKED` |
| `Document` | 기존 상태 | 변경 없음 | 변경 없음 |

### 3.4 재사용할 선행 계약

선행 Attempt 설계와 구현은 다음 계약을 확정했다.

- Worker는 Attempt 시작 응답의 Attempt ID를 이후 파싱·Embedding·완료·실패 흐름의 실행 식별자로
  보존한다.
- 후속 단계는 Job ID, Attempt ID, Worker ID, Claim Token을 함께 전달한다.
- 외부 I/O 전후의 짧은 Transaction마다 Job을 먼저 잠그고 현재 소유권과 Lease를 다시 검증한다.
- 모든 관련 기능은 Job을 먼저 잠근 뒤 Attempt 또는 다른 종속 데이터를 조회하는 Lock 순서를 지킨다.
- Claim Token은 응답, 로그, 이벤트, Metric Label에 노출하지 않는다.

이 계약 때문에 초기 요구 초안의 Job 바로 아래 `/chunks` 경로만 사용하는 대신 Attempt ID를 명시적으로
포함하는 API를 사용한다.

### 3.5 재사용할 Infrastructure

- `FileStorageService`와 `MinioStorageService`
- `MinioClient`, `MinioProperties`
- `Clock` Bean
- `ResponseUtils`, `ApiResponse`
- `DocGridException`, `ErrorCode`, `GlobalExceptionHandler`
- Jakarta Bean Validation
- JPA Pessimistic Write Lock과 PostgreSQL Row Lock
- 실제 OpenSQL 격리 Schema를 사용하는 통합 테스트 패턴
- `minio-integration` Tag와 기존 `minioIntegrationTest` Gradle Task

## 4. 가정과 결정 사항

### 4.1 확인된 가정

- 파싱 요청 전 Worker는 Job Claim과 Attempt 시작을 완료했다.
- 처리 대상 Job은 `PROCESSING`이며 현재 Worker, Claim Token, 미래 Lease 만료 시각을 가진다.
- 현재 Claim과 연결된 Attempt는 `STARTED` 상태다.
- Production 업로드 경로는 TXT와 Markdown만 허용하고 파일 크기를 10MB 이하로 제한한다.
- 업로드 경로가 생성한 Version에는 FileObject, 원본 파일명, Content Type Snapshot이 존재한다.
- MinIO의 원본은 업로드 이후 변경하지 않는 불변 Object다.
- Chunk 저장 이후 Version의 Chunk를 수정하거나 덮어쓰지 않는다.
- PostgreSQL이 상태 전이와 중복 저장의 최종 동시성 제어 지점이다.

### 4.2 처리 대상 Version 결정

처리 대상은 `Document.currentVersion`이 아니라 잠근 Job의 `documentVersion`이다.

최초 업로드에서는 두 값이 같지만, 후속 Version 업로드에서는 새 Version이 색인을 완료할 때까지
`Document.currentVersion`이 기존 검색 가능 Version을 가리킨다. currentVersion을 사용하면 새 Job이
이전 파일을 다시 Chunking하는 오류가 발생한다.

Job과 Version 연관이 없거나 Version의 FileObject가 없으면 정상적인 사용자 오류가 아니라 DB와
Application 불변식 위반으로 처리한다.

### 4.3 API 경로 결정

| 선택지 | 장점 | 문제 |
| --- | --- | --- |
| Job 아래 `/chunks`를 유지하고 Body에 Attempt ID 추가 | 초기 요구 경로와 유사함 | 실행 식별자가 Body에 묻히고 후속 단계 경로 규칙이 불명확함 |
| Attempt 아래 `/chunks`를 배치 | Job과 실행 세대의 관계가 명시적이며 후속 단계와 일관됨 | Path가 길어짐 |

권장안은 `/admin/indexing-jobs/{jobId}/attempts/{attemptId}/chunks`다.

기존 애플리케이션은 `/api` Prefix 없이 `/admin`을 사용하고 `SecurityConfig`가 `/admin/**`를
보호한다. 따라서 초기 요구의 `/api/admin` 표기는 현재 Router 계약에 맞게 `/admin`으로 조정한다.

### 4.4 Markdown 처리 결정

| 선택지 | 장점 | 문제 |
| --- | --- | --- |
| Markdown AST Library로 문법 제거 | 검색·Embedding에 자연어만 전달 가능 | 새 의존성, Link·Code Block·표 보존 정책, Offset 재매핑이 추가됨 |
| UTF-8 Markdown 원문 보존 | TXT와 같은 결정적 Offset, 추가 Library 없음, 구현 범위가 작음 | Markdown 기호와 URL이 Chunk에 포함됨 |

MVP 권장안은 Markdown 원문을 보존하는 것이다.

TXT와 Markdown 모두 같은 UTF-8 Text Parser를 사용한다. Parser 선택 전에 `Document.documentType`과
`DocumentVersion.contentType`을 함께 검증하되, Markdown 문법을 제거하지 않는다.
`section_title`은 null로 저장한다. Heading 기반 Section 추출은 의미 Chunking과 함께 후속 기능으로
분리한다.

### 4.5 텍스트 정규화 결정

Parser는 다음 순서로 Canonical Text를 만든다.

1. 원본 Byte를 오류 보고 모드의 UTF-8 Decoder로 해석한다.
2. 맨 앞에 UTF-8 BOM이 한 개 있으면 제거한다.
3. CRLF와 단독 CR을 LF로 통일한다.
4. 본문 앞뒤 공백과 줄바꿈은 삭제하지 않는다.
5. 전체 결과가 공백뿐이면 빈 문서 오류로 거부한다.

Offset과 Hash는 원본 Byte가 아니라 이 Canonical Text를 기준으로 한다. 이 규칙을 바꾸면 같은 파일의
Chunk 경계와 Hash가 달라지므로 정책 변경으로 취급해야 한다.

### 4.6 문자 범위와 Chunk 경계 결정

Java의 UTF-16 Code Unit 인덱스를 그대로 사용하면 Emoji와 일부 확장 문자를 Surrogate Pair 중간에서
자를 수 있다.

Chunk 크기와 `char_start`, `char_end`는 Unicode Code Point 기준으로 계산한다.

- `char_start`는 Canonical Text에서 포함되는 첫 Code Point의 0 기반 위치다.
- `char_end`는 포함되지 않는 끝 Code Point 위치다.
- `chunk_text`는 해당 반열린 구간의 정확한 문자열이다.
- `chunk_index`는 0부터 연속 증가한다.
- 마지막 Chunk가 원문 끝에 도달하면 추가 Overlap 전용 Chunk를 만들지 않는다.

### 4.7 Token 수 결정

현재 프로젝트에는 BGE-M3 Tokenizer가 없고 이 단계는 Job의 Embedding Model을 호출하지 않는다.
모델 Token 수를 가장하는 대신, MVP에서는 Unicode 공백으로 구분된 비어 있지 않은 Text Run 수를
결정적인 추정치로 저장한다.

`token_count`는 이 단계에서 Context Window를 강제하는 값이 아니다. 실제 모델별 Token 수가 필요한
시점에는 Tokenizer 연동과 기존 데이터 의미를 별도 Migration 또는 Metadata Version으로 다뤄야 한다.

### 4.8 Chunk 설정 결정

기본값은 다음과 같다.

| 설정 | 기본값 | 의미 |
| --- | ---: | --- |
| Chunk 크기 | 1,000 Code Point | 한 Chunk가 포함할 최대 문자 수 |
| Overlap | 200 Code Point | 인접 Chunk가 공유할 문자 수 |
| Step | 800 Code Point | 다음 Chunk 시작 위치 증가량 |

Chunk 크기는 양수여야 하고 Overlap은 0 이상이면서 Chunk 크기보다 작아야 한다. 잘못된 조합은 요청
처리 중 오류가 아니라 애플리케이션 시작 단계의 Configuration Validation 오류로 차단한다.

### 4.9 재호출 결정

| Version 상태 | 처리 |
| --- | --- |
| `UPLOADED` | `PARSING`으로 전환하고 최초 처리를 시작 |
| `PARSING`이고 Chunk 없음 | 같은 현재 또는 새 유효 Attempt가 읽기·파싱을 재개 |
| `CHUNKED`이고 Chunk 있음 | MinIO를 다시 읽지 않고 기존 결과를 `200 OK`로 반환 |
| `PARSING`인데 Chunk 존재 | 원자 저장 불변식 위반으로 서버 오류 |
| `CHUNKED`인데 Chunk 없음 | 상태·데이터 불변식 위반으로 서버 오류 |
| `EMBEDDING`, `INDEXED`, `FAILED` | Chunking 불가 상태 오류 |

`PARSING` 재개를 허용하는 이유는 첫 Transaction Commit 후 Process가 종료되거나 MinIO 오류가 발생할
수 있기 때문이다. 같은 요청이 동시에 실행되면 중복 파일 읽기와 계산은 일어날 수 있지만, 두 번째
저장 Transaction은 이미 Commit된 `CHUNKED` 결과를 재생하고 Chunk를 덮어쓰지 않는다.

### 4.10 열린 결정

구현을 막는 열린 결정은 없다.

API 경로, Markdown 원문 보존, Unicode Code Point 경계, 공백 기반 Token 추정, `PARSING` 재개를
본 설계의 권장 계약으로 사용한다. 이 중 하나를 바꾸려면 구현 전에 API 또는 데이터 의미 변경으로
명시적으로 승인받아야 한다.

## 5. 핵심 규칙과 불변식

### 5.1 실행 소유권 불변식

모든 상태 변경 Transaction은 다음 조건을 모두 만족해야 한다.

1. Job이 존재한다.
2. Job 상태가 `PROCESSING`이다.
3. 현재 Worker, Claim Token, Lock 시작, Lease 만료 값이 모두 존재한다.
4. 요청 Worker ID가 Job의 현재 Worker ID와 같다.
5. 요청 Claim Token이 Job의 현재 Claim Token과 같다.
6. 현재 시각이 Lease 만료 시각보다 이르다.
7. Job과 현재 Claim Token으로 조회한 Attempt가 존재한다.
8. 조회한 Attempt ID가 Path의 Attempt ID와 같다.
9. Attempt의 Worker가 현재 Job Worker와 같다.
10. Attempt 상태가 `STARTED`다.

Claim Token은 문자열만 같은지 확인하는 값이 아니라 현재 Job과 Attempt를 같은 Claim 세대로 묶는
증명 값이다.

### 5.2 Lock 순서 불변식

상태 변경 경로는 항상 다음 순서로 잠금과 조회를 수행한다.

1. Embedding Job 쓰기 행 잠금
2. 현재 시각 계산과 Job 소유권 검증
3. 현재 Claim의 Attempt 검증
4. Job이 참조하는 DocumentVersion 쓰기 행 잠금
5. 기존 Chunk 상태 조회와 상태 전이

Attempt 또는 Version을 먼저 잠근 뒤 Job을 잠그는 반대 순서를 만들지 않는다.

### 5.3 파일 선택 불변식

- Job이 참조하는 Version만 처리한다.
- Version이 속한 Document의 Type으로 Parser 지원 여부를 판단한다.
- Version의 Content Type Snapshot으로 현재 업로드 형식이 지원되는지 교차 검증한다.
- FileObject의 Bucket과 Object Key를 사용해 MinIO Object를 읽는다.
- 전역 MinIO 기본 Bucket으로 FileObject의 저장 위치를 덮어쓰지 않는다.
- FileObject의 Content Type은 Dedup 재사용으로 Version Snapshot과 다를 수 있으므로 Parser 선택의
  단독 기준으로 사용하지 않는다.

지원 조합은 다음과 같다.

| Document Type | 허용 Version Content Type |
| --- | --- |
| `TXT` | `text/plain` |
| `MD` | `text/markdown`, `text/plain` |

### 5.4 Chunk 불변식

- 하나의 Version에서 Chunk Index는 0부터 끊김 없이 증가한다.
- 모든 Chunk는 같은 Version을 참조한다.
- 모든 Chunk Text는 Canonical Text의 정확한 반열린 범위다.
- 인접 Chunk의 겹침은 설정된 Overlap과 같다. 마지막 경계에서는 원문 길이가 우선한다.
- `content_hash`는 정확한 `chunk_text` UTF-8 Byte의 SHA-256 소문자 Hex다.
- 같은 Chunk Text는 위치와 무관하게 같은 Hash를 가진다.
- `token_count`는 음수가 아니며 공백 기반 추정 규칙을 사용한다.
- TXT와 Markdown의 `page_no`, `section_title`, `metadata_json`은 이번 범위에서 null이다.
- Chunk 목록은 비어 있을 수 없다.
- 생성된 Chunk는 수정하거나 덮어쓰지 않는다.

### 5.5 상태·이벤트 불변식

- `UPLOADED`에서 `PARSING`으로 처음 전환할 때 `PARSE_STARTED` 이벤트를 같은 Transaction에 한 번
  저장한다.
- 이미 `PARSING`인 재개 요청은 `PARSE_STARTED`를 중복 저장하지 않는다.
- Chunk 전체 저장과 `PARSING`에서 `CHUNKED` 전환 및 `CHUNKED` 이벤트는 같은 Transaction이다.
- Chunk 저장이 Rollback되면 Version은 `PARSING`으로 남고 `CHUNKED` 이벤트는 없다.
- `CHUNKED` 재생은 새 Chunk와 새 이벤트를 만들지 않는다.
- 이 기능은 Job, Attempt, Document의 상태를 변경하지 않는다.

### 5.6 원자성 불변식

Version 하나의 Chunk는 모두 저장되거나 하나도 저장되지 않는다.

DB Insert 오류, Unique 충돌, 상태 변경 오류, 이벤트 저장 오류, Commit 오류 중 하나라도 발생하면
마지막 Transaction의 Chunk Insert, Version 상태 변경, 이벤트 Insert를 전부 Rollback한다.

## 6. 전체 동작 흐름

### 6.1 최초 정상 요청

1. ADMIN 인증을 통과한 요청이 Job ID, Attempt ID, Worker ID, Claim Token을 전달한다.
2. Controller가 양수 Path 값과 Request Body 형식을 검증한다.
3. Transaction이 없는 `DocumentParsingService`가 첫 번째 Transaction Service를 호출한다.
4. 첫 번째 Transaction이 대상 Job을 쓰기 잠금으로 조회한다.
5. 잠금 획득 후 `Clock`으로 현재 시각을 계산한다.
6. 공통 Validator가 Job 상태, 소유권 필드, Worker, Token, Lease를 검증한다.
7. 현재 Job과 Claim Token의 Attempt를 조회해 Path Attempt ID, Worker, `STARTED` 상태를 검증한다.
8. Job이 직접 참조하는 Version을 쓰기 잠금으로 조회한다.
9. Version의 Document Type, Content Type, FileObject 연관과 저장 위치를 검증한다.
10. 기존 Chunk가 없는지 확인한다.
11. Version을 `UPLOADED`에서 `PARSING`으로 전환한다.
12. 같은 Transaction에 `PARSE_STARTED` 이벤트를 저장한다.
13. 외부 작업에 필요한 Version ID, Document Type, Bucket, Object Key를 불변 Snapshot으로 반환한다.
14. 첫 번째 Transaction을 Commit하고 모든 DB 행 잠금을 해제한다.
15. `FileStorageService`가 FileObject의 Bucket과 Object Key로 MinIO Object 전체를 읽고 Stream을 닫는다.
16. Text Parser가 Byte를 엄격한 UTF-8로 Decode하고 BOM과 줄바꿈을 정규화한다.
17. 빈 Canonical Text가 아닌지 확인한다.
18. `FixedSizeChunker`가 설정된 크기와 Overlap으로 Code Point 범위를 계산한다.
19. 각 Chunk의 Index, Text, Token 추정치, 시작·끝 범위, SHA-256 Hash를 계산한다.
20. `DocumentParsingService`가 두 번째 Transaction Service를 호출한다.
21. 두 번째 Transaction이 Job을 다시 쓰기 잠금으로 조회하고 잠금 후 현재 시각을 계산한다.
22. Job 소유권, Lease, Attempt를 다시 검증한다.
23. 같은 Version을 쓰기 잠금으로 조회하고 `PARSING` 상태와 기존 Chunk 부재를 확인한다.
24. 모든 Chunk를 `saveAllAndFlush`로 저장한다.
25. Version을 `CHUNKED`로 전환한다.
26. 같은 Transaction에 `CHUNKED` 이벤트를 저장한다.
27. Commit 후 Transaction Service가 Job ID, Attempt ID, Version ID, Chunk 수, `CHUNKED` 상태를 반환한다.
28. Controller는 최초 생성 결과를 `201 Created`로 반환한다.

### 6.2 `PARSING` 상태 재개

1. 첫 번째 Transaction은 현재 소유권과 Attempt를 동일하게 검증한다.
2. Version이 `PARSING`이고 기존 Chunk가 없으면 상태를 다시 변경하지 않는다.
3. 새 `PARSE_STARTED` 이벤트를 만들지 않고 File Snapshot만 반환한다.
4. MinIO 읽기, 파싱, Chunk 계산, 두 번째 Transaction을 정상 흐름과 동일하게 수행한다.
5. Chunk를 실제로 처음 Commit한 요청은 `201 Created`를 반환한다.

새 Claim 세대가 이전 실패의 `PARSING` Version을 재개할 수도 있다. 현재 유효한 Attempt만 저장 권한을
가지며 과거 Attempt는 거부된다.

### 6.3 `CHUNKED` 상태 재생

1. 첫 번째 Transaction에서 현재 Job 소유권과 Attempt를 먼저 검증한다.
2. Version이 `CHUNKED`이면 저장된 Chunk 수를 조회한다.
3. Chunk 수가 1 이상이면 기존 Version ID와 Chunk 수를 반환한다.
4. MinIO 읽기, Parser, Chunker, 두 번째 Transaction은 호출하지 않는다.
5. Controller는 `200 OK`를 반환한다.

소유권과 Lease가 이미 만료된 과거 호출은 `CHUNKED` 결과도 조회할 수 없다.

### 6.4 동시 중복 요청

1. 두 요청이 첫 번째 Transaction의 같은 Job 잠금을 차례로 획득한다.
2. 첫 요청만 `UPLOADED`에서 `PARSING`으로 전환하고 이벤트를 저장한다.
3. 두 요청 모두 DB Transaction 밖에서 같은 파일을 읽고 같은 Chunk를 계산할 수 있다.
4. 두 번째 Transaction은 다시 Job 잠금으로 직렬화된다.
5. 먼저 진입한 요청이 Chunk와 `CHUNKED` 이벤트를 Commit한다.
6. 나중 요청은 `CHUNKED`와 기존 Chunk를 확인하고 새 Insert 없이 재생 결과를 반환한다.
7. DB에는 Chunk Set 하나, `PARSE_STARTED` 하나, `CHUNKED` 하나만 남는다.

### 6.5 MinIO 또는 Parser 실패

1. 첫 번째 Transaction은 이미 `PARSING`과 `PARSE_STARTED`를 Commit했다.
2. MinIO Object 없음, Storage 장애, UTF-8 오류, 빈 텍스트가 발생하면 두 번째 Transaction을 시작하지
   않는다.
3. Chunk와 `CHUNKED` 이벤트는 생성되지 않는다.
4. Version은 `PARSING`, Job은 `PROCESSING`, Attempt는 `STARTED`로 남는다.
5. 현재 또는 새 유효 Attempt가 같은 Endpoint를 호출해 재개할 수 있다.
6. 실패 상태, Attempt 종료, `PARSE_FAILED` 기록은 후속 실패 처리 기능이 담당한다.

### 6.6 외부 작업 중 소유권 만료 또는 교체

1. 첫 번째 Transaction에서 유효했던 Lease가 MinIO 읽기·파싱 중 만료될 수 있다.
2. 두 번째 Transaction은 Job 잠금 후 새 현재 시각으로 Lease와 Token을 다시 검증한다.
3. Lease가 만료됐거나 Token이 교체됐으면 Chunk Insert 전에 요청을 거부한다.
4. 계산된 메모리 결과는 버리고 DB에는 저장하지 않는다.
5. Version은 `PARSING`으로 남고 후속 Recovery 또는 새 Attempt가 재개한다.

## 7. 계층별 구현 설계

### 7.1 Controller

`IndexingJobAdminController`에 Attempt 실행 단위의 Chunk 생성 Endpoint를 추가한다.

책임은 다음으로 제한한다.

- Job ID와 Attempt ID의 양수 검증
- Worker ID와 Claim Token Request 검증
- `DocumentParsingService` 호출
- 최초 저장과 멱등 재생을 각각 `201`과 `200`으로 변환
- 공통 `ApiResponse` 적용
- Swagger에 실행 전제, Transaction 분리, 성공·재생, 오류 조건 설명

Job·Attempt 조회, Token 비교, 상태 전이, MinIO 읽기, Chunk 계산, Entity 생성은 Controller에 두지 않는다.

### 7.2 비 Transaction Orchestration Service

새 `DocumentParsingService`는 전체 흐름을 조정하되 클래스 또는 Public 진입점에 DB Transaction을
적용하지 않는다.

책임은 다음과 같다.

- 첫 번째 짧은 Transaction 호출
- `CHUNKED` 재생이면 즉시 기존 결과 반환
- File Snapshot으로 MinIO 원본 읽기
- Document Type과 Text Parser 연결
- Canonical Text를 `FixedSizeChunker`에 전달
- 두 번째 짧은 Transaction 호출
- 저장 생성 여부와 응답 전달

이 Service를 별도로 두는 이유는 MinIO와 CPU 작업을 DB Transaction 밖에 둔다는 경계를 코드 구조로
강제하기 위해서다.

### 7.3 Transaction Service

새 `DocumentChunkTransactionService`는 두 개의 짧은 Transaction 진입점을 제공한다.

준비 단계 책임:

- Job 잠금
- 현재 소유권·Lease·Attempt 검증
- Version 잠금과 Job 대상 Version 일치 검증
- FileObject와 지원 형식 검증
- 기존 Chunk·Version 상태 검증
- 최초 `PARSING` 전환과 `PARSE_STARTED` 이벤트
- 외부 작업에 필요한 불변 Snapshot 또는 기존 `CHUNKED` 결과 반환

완료 단계 책임:

- Job 잠금과 소유권·Lease·Attempt 재검증
- Version 잠금과 준비 단계 Version ID 일치 검증
- 동시 요청이 먼저 만든 `CHUNKED` 결과 재생
- `PARSING`과 Chunk 부재 확인
- Chunk Entity 전체 저장과 Flush
- `CHUNKED` 상태 전환과 이벤트 저장
- Response와 최초 생성 여부 반환

두 진입점은 같은 클래스에 둘 수 있지만 반드시 다른 Spring Bean인 `DocumentParsingService`에서
호출한다. 같은 객체 내부 호출에 의존해 `@Transactional` Proxy가 우회되는 구조를 만들지 않는다.

### 7.4 공통 Job 소유권 Validator

선행 `EmbeddingJobAttemptService`의 Private 소유권 검증을 새 `EmbeddingJobOwnershipValidator`로
추출한다.

이 Validator는 Repository를 호출하거나 Transaction을 시작하지 않는다. 이미 잠긴 Job과 요청 Worker,
Token, 잠금 후 현재 시각을 받아 다음 정책만 검증한다.

- `PROCESSING` 상태
- 소유권 필드 완전성
- Worker ID 일치
- Claim Token 일치
- Lease 유효성

Attempt 시작 Service와 Chunk Transaction Service가 같은 Validator를 사용한다. 기존 Attempt API의
오류 코드와 검증 순서는 유지한다.

Attempt ID, Attempt 상태, Attempt Worker 검증은 Chunk Transaction Service가 현재 Claim Token으로
조회한 Attempt에 대해 수행한다. 이는 Chunk 흐름에만 필요한 실행 Context 검증이므로 공통 Job
Validator에 섞지 않는다.

### 7.5 Text Parser

새 `TextDocumentParser`는 TXT와 Markdown Byte를 Canonical Text로 바꾸는 순수 Component다.

책임은 다음과 같다.

- 엄격한 UTF-8 Decode
- 선택적 선두 BOM 제거
- 줄바꿈 통일
- 공백뿐인 결과 거부
- 원문 Markdown 문법과 일반 공백 보존

Parser는 MinIO, Repository, Entity, Transaction, Chunk 크기 설정을 알지 않는다.

Markdown 전용 구현체는 이번 범위에서 만들지 않는다. TXT와 Markdown의 처리 차이가 없는데 동일한
클래스 두 개를 만드는 것은 현재 저장소의 단순성 원칙에 어긋난다.

### 7.6 Fixed Size Chunker

새 `FixedSizeChunker`는 Canonical Text와 `DocumentChunkingProperties`만 사용해
`DocumentChunkDraft` 목록을 만든다.

책임은 다음과 같다.

- Unicode Code Point 길이와 경계 계산
- 0 기반 연속 Chunk Index 할당
- Chunk 크기, Overlap, 마지막 Chunk 종료 조건 적용
- 정확한 Chunk Text 추출
- 반열린 Char 범위 기록
- 공백 기반 Token 추정치 계산
- SHA-256 Content Hash 계산
- Page, Section, Metadata를 이번 범위의 null 값으로 유지

`DocumentChunkDraft`는 아직 DB Entity가 아닌 계산 결과다. 외부 작업 단계가 JPA Entity와 Persistence
Context를 들고 다니지 않게 한다.

### 7.7 Repository

`DocumentVersionRepository`에는 ID 기반 Pessimistic Write 조회를 추가한다.

`DocumentChunkRepository`를 새로 만들고 다음 책임만 둔다.

- Version별 Chunk 존재 여부 확인
- Version별 Chunk 수 조회
- 모든 Chunk 저장과 Flush

`EmbeddingJobRepository.findByIdForUpdate`와
`EmbeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken`은 선행 변경을 그대로 재사용한다.

`FileObjectRepository`를 다시 조회하지 않는다. Job에서 잠근 Version의 FileObject 연관을 사용해
처리 대상과 저장 위치를 한 Context로 유지한다.

`IndexingEventRepository`는 기존 저장 기능을 그대로 사용한다.

### 7.8 Entity와 Domain

`DocumentVersion.markParsing`은 `UPLOADED`에서만, `markChunked`는 `PARSING`에서만 호출되도록 Domain
상태 전이 Guard를 추가한다.

재개 요청은 이미 `PARSING`인 Version에 `markParsing`을 다시 호출하지 않는다. 잘못된 상태에서 Entity
메서드를 직접 호출하면 `IllegalStateException`으로 구현 오류를 빠르게 드러낸다.

`DocumentChunk`는 기존 Builder와 Mapping을 사용한다. 저장 전 Transaction Service가 Draft의
Version, Index, Text, 범위, Token 추정치, Hash를 검증하고 Entity로 변환한다.

### 7.9 Storage

`FileStorageService`에 저장된 Object 전체를 읽는 책임을 추가한다. 입력은 Bucket과 Object Key를 가진
`StoredFile`, 출력은 닫힌 Stream과 분리된 Byte 배열이다.

`MinioStorageService`는 다음을 보장한다.

- `GetObject` 호출에 FileObject의 Bucket과 Object Key 사용
- 응답 Stream을 Service 내부에서 닫음
- Object 없음과 그 밖의 Storage 장애 구분
- 오류 로그에 Job Token이나 파일 본문을 포함하지 않음
- 기존 Store와 Delete 동작 유지

10MB 업로드 제한이 있으므로 MVP에서는 전체 Byte를 메모리에 읽는다. Streaming Parser는 후속 대용량
문서 지원에서 검토한다.

### 7.10 Request와 Response DTO

새 Request DTO는 Worker ID와 Claim Token을 받는다. 기존 Attempt 시작 Request와 동일하게 Worker ID는
필수 양수, Token은 최대 36자의 Canonical UUID 형식으로 검증한다.

새 Response DTO는 다음 필드만 노출한다.

| 필드 | 의미 |
| --- | --- |
| `jobId` | 처리한 Embedding Job ID |
| `attemptId` | 현재 실행 Attempt ID |
| `documentVersionId` | Job이 직접 가리키는 처리 Version ID |
| `chunkCount` | 해당 Version에 저장된 Chunk 수 |
| `versionStatus` | `CHUNKED` |

Claim Token, Bucket, Object Key, 원본 파일명, Chunk 본문, 내부 상태 메시지는 응답하지 않는다.

별도 Converter는 만들지 않는다. API 응답은 Entity 그래프 변환이 아니라 Transaction 결과의 식별자와
집계 값만 사용하고, Chunk Draft에서 Entity로의 변환은 저장 Transaction 책임에 포함된다.

### 7.11 Configuration

새 `DocumentChunkingProperties`는 `document.chunking` Prefix를 사용한다.

| Property | 환경변수 | 기본값 |
| --- | --- | ---: |
| `chunk-size` | `DOCUMENT_CHUNK_SIZE` | 1000 |
| `overlap` | `DOCUMENT_CHUNK_OVERLAP` | 200 |

Configuration 클래스는 양수 Chunk 크기와 유효한 Overlap 조합을 시작 시점에 검증한다.

## 8. 파일 변경 계획

### 8.1 Production과 Configuration

| 구분 | 경로 | 책임과 변경 이유 |
| --- | --- | --- |
| CREATE | `docs/design/text-parsing-chunk-storage.md` | 구현 기준, 계약, 테스트, 인계 보존 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/document/config/DocumentChunkingProperties.java` | Chunk 크기·Overlap 바인딩과 조합 검증 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/document/repository/DocumentChunkRepository.java` | Version별 존재·수 조회와 Chunk 전체 저장 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/document/service/DocumentChunkDraft.java` | DB 저장 전 결정적인 Chunk 계산 결과 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/document/service/TextDocumentParser.java` | UTF-8 Decode와 Canonical Text 정규화 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/document/service/FixedSizeChunker.java` | Code Point 기반 고정 크기·Overlap Chunk 계산 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/document/service/DocumentParsingService.java` | 두 DB Transaction 사이에서 MinIO·Parser·Chunker 조정 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/document/service/command/DocumentChunkTransactionService.java` | 준비·완료 상태 전이, 소유권 재검증, Chunk·이벤트 원자 저장 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/embedding/service/command/EmbeddingJobOwnershipValidator.java` | 잠긴 Job의 공통 소유권·Lease 정책 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/embedding/dto/request/CreateDocumentChunksRequest.java` | Worker ID와 Claim Token 입력·검증·Swagger 계약 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/embedding/dto/response/DocumentChunksResponse.java` | Job·Attempt·Version·Chunk 수·Version 상태 응답 |
| MODIFY | `src/main/java/com/opensource/docgrid/domain/document/entity/DocumentVersion.java` | PARSING·CHUNKED 상태 전이 Guard와 설명 보강 |
| MODIFY | `src/main/java/com/opensource/docgrid/domain/document/repository/DocumentVersionRepository.java` | 대상 Version ID의 쓰기 행 잠금 조회 추가 |
| MODIFY | `src/main/java/com/opensource/docgrid/domain/document/storage/FileStorageService.java` | 저장된 Object Byte 읽기 계약 추가 |
| MODIFY | `src/main/java/com/opensource/docgrid/domain/document/storage/MinioStorageService.java` | GetObject, Stream 종료, Object 없음·장애 매핑 |
| MODIFY | `src/main/java/com/opensource/docgrid/domain/embedding/service/command/EmbeddingJobAttemptService.java` | Private Job 소유권 검증을 공통 Validator로 위임 |
| MODIFY | `src/main/java/com/opensource/docgrid/domain/embedding/controller/IndexingJobAdminController.java` | Attempt 단위 Chunk 생성 Endpoint와 Swagger 계약 추가 |
| MODIFY | `src/main/java/com/opensource/docgrid/global/exception/ErrorCode.java` | Attempt, Version 상태, Parser, File, Chunk 불변식 오류 추가 |
| MODIFY | `src/main/resources/application.yml` | Chunk 크기·Overlap 기본값과 환경변수 연결 |
| MODIFY | `.env.example` | 선택적 Chunk 환경변수와 기본값 문서화 |

### 8.2 Test

| 구분 | 경로 | 검증 책임 |
| --- | --- | --- |
| CREATE | `src/test/java/com/opensource/docgrid/domain/document/config/DocumentChunkingPropertiesTest.java` | 기본값과 잘못된 크기·Overlap 시작 실패 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/document/entity/DocumentVersionTest.java` | PARSING·CHUNKED 정상 전이와 잘못된 전이 차단 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/document/service/TextDocumentParserTest.java` | UTF-8, BOM, 줄바꿈, Markdown 보존, 빈 문서, 잘못된 Byte |
| CREATE | `src/test/java/com/opensource/docgrid/domain/document/service/FixedSizeChunkerTest.java` | 경계, Overlap, Unicode, Token 추정, Hash 결정성 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/document/service/DocumentParsingServiceTest.java` | Transaction 사이 외부 작업 조정과 조기 재생 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/document/service/command/DocumentChunkTransactionServiceTest.java` | 준비·완료 상태, 소유권, Attempt, 원자 저장, 오류 흐름 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/document/storage/MinioStorageServiceTest.java` | GetObject 성공, Stream 종료, 없음·장애 오류 매핑 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/embedding/service/command/EmbeddingJobOwnershipValidatorTest.java` | 기존 소유권·Lease 정책의 독립 회귀 검증 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/document/integration/DocumentChunkingIntegrationTest.java` | 실제 OpenSQL 상태·Chunk·이벤트 원자성과 동시성 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/document/integration/minio/MinioDocumentReadIntegrationTest.java` | 실제 MinIO Store 후 Read와 없는 Object 처리 |
| MODIFY | `src/test/java/com/opensource/docgrid/domain/embedding/service/command/EmbeddingJobAttemptServiceTest.java` | 공통 Validator 위임 후 Attempt 시작 회귀 |
| MODIFY | `src/test/java/com/opensource/docgrid/domain/embedding/controller/IndexingJobAdminControllerTest.java` | 신규 HTTP, Validation, Security, 오류 계약 |
| MODIFY | `src/test/java/com/opensource/docgrid/domain/document/integration/minio/MinioUploadConcurrencyIntegrationTest.java` | 확장된 Storage Interface를 Test Wrapper가 위임하도록 보강 |

DELETE 대상은 없다.

### 8.3 이미 존재해 변경하지 않는 파일

| 경로 | 이유 |
| --- | --- |
| `src/main/java/com/opensource/docgrid/domain/document/entity/DocumentChunk.java` | 필요한 컬럼과 불변 데이터 Mapping이 이미 존재 |
| `src/main/java/com/opensource/docgrid/domain/document/entity/FileObject.java` | Bucket·Object Key·Content Type·크기·Hash가 이미 존재 |
| `src/main/java/com/opensource/docgrid/domain/document/repository/FileObjectRepository.java` | Job→Version→FileObject 연관을 사용하므로 추가 조회 불필요 |
| `src/main/java/com/opensource/docgrid/domain/embedding/repository/EmbeddingJobRepository.java` | 선행 변경의 ID 쓰기 잠금 조회를 재사용 |
| `src/main/java/com/opensource/docgrid/domain/worker/repository/EmbeddingJobAttemptRepository.java` | 선행 변경의 Job·Claim Token 조회를 재사용 |
| `src/main/java/com/opensource/docgrid/domain/worker/enums/IndexingEventType.java` | `PARSE_STARTED`, `CHUNKED`가 이미 존재 |
| `src/main/java/com/opensource/docgrid/domain/worker/repository/IndexingEventRepository.java` | 기존 append-only 저장 기능으로 충분 |
| `src/main/java/com/opensource/docgrid/global/config/SecurityConfig.java` | 기존 `/admin/**` ADMIN 정책이 Endpoint를 포함 |
| `build.gradle` | 필요한 JPA, Validation, MinIO, Jackson, Test 의존성과 Task가 이미 존재 |
| `src/main/resources/db/migration/V15__create_document_chunks.sql` | 기존 Migration은 수정하지 않으며 새 Schema도 불필요 |

## 9. DB와 데이터 변경

### 9.1 Migration

추가 없음.

`document_chunks` 테이블, 필드, Version·Chunk Index Unique Constraint, 관련 Index가 이미 존재한다.
이미 적용된 Flyway Migration을 수정하지 않는다.

### 9.2 Constraint와 Index

추가 없음.

기존 `uk_document_chunks_document_version_id_chunk_index`를 최종 중복 방어선으로 사용한다.
Version별 존재·수 조회에는 기존 `idx_document_chunks_document_version_id`를 사용한다.

현재 `content_hash`가 nullable인 이유는 기존 Seed와 Legacy Row 호환성이다. 이번 기능이 생성하는 신규
Chunk에는 항상 Hash를 넣되, Backfill 없이 DB `NOT NULL`을 추가하지 않는다.

### 9.3 Backfill

추가 없음.

기존 개발 Seed Chunk의 Hash, Page, Section, Metadata를 이번 기능에서 수정하지 않는다.

### 9.4 호환성과 배포

- 새 애플리케이션은 기존 Schema에서 바로 동작한다.
- 새 Chunk는 기존 검색·Citation FK 구조와 호환된다.
- `CHUNKED` Version은 기존 상태 조회가 이미 처리 중 상태로 해석한다.
- 새 Configuration은 기본값이 있어 환경변수를 설정하지 않은 기존 배포도 시작할 수 있다.
- 선행 Attempt 변경이 병합되지 않은 환경에서는 컴파일 계약 자체가 없으므로 배포 순서를 지켜야 한다.

### 9.5 Rollback

Schema 변경이 없으므로 Application Version만 이전으로 되돌릴 수 있다.

이미 Commit된 Chunk와 `CHUNKED` Version은 유효한 파이프라인 데이터이므로 Rollback 배포에서 삭제하지
않는다. 첫 Transaction만 Commit되어 `PARSING`으로 남은 Version도 자동 삭제하지 않으며, 재배포 또는
후속 복구 기능이 재개한다.

## 10. API와 오류 계약

### 10.1 Endpoint

| 항목 | 계약 |
| --- | --- |
| Method | `POST` |
| Path | `/admin/indexing-jobs/{jobId}/attempts/{attemptId}/chunks` |
| Request Content Type | `application/json` |
| Response Content Type | `application/json` |
| 인증 | JWT 인증과 ADMIN 역할 |
| 목적 | 현재 Attempt가 소유한 Job의 원본을 파싱하고 Chunk 저장 |

### 10.2 Path와 Request

| 입력 | 위치 | 검증 |
| --- | --- | --- |
| Job ID | Path | 필수 양수 |
| Attempt ID | Path | 필수 양수 |
| Worker ID | Body | 필수 양수 |
| Claim Token | Body | 필수, 공백 불가, 최대 36자, Canonical UUID |

Claim Token은 Query String이나 Path에 넣지 않는다.

### 10.3 성공 응답

최초 Chunk 저장 Commit은 `201 Created`를 반환한다.

같은 현재 실행의 `CHUNKED` 재호출과 동시 요청의 후발 호출은 `200 OK`를 반환한다.

두 응답은 동일한 Body 형태를 사용하고 HTTP 상태로 생성과 재생을 구분한다. Body에는 Job ID,
Attempt ID, Version ID, Chunk 수, `CHUNKED` Version 상태가 포함된다.

### 10.4 오류 응답

| 상황 | HTTP | 권장 오류 코드 |
| --- | ---: | --- |
| Path 또는 Body Validation 실패 | 400 | `COMMON-002` |
| 인증되지 않았거나 ADMIN 역할 없음 | 403 | 기존 Security 동작 |
| Job 없음 | 404 | `EMBEDDING-JOB-001` |
| MinIO Object 없음 | 404 | 신규 `DOCUMENT-STORAGE-002` |
| Job 상태가 `PROCESSING`이 아님 | 409 | `EMBEDDING-JOB-002` |
| Worker 또는 Claim Token 불일치 | 409 | `EMBEDDING-JOB-003` |
| Lease 만료 | 409 | `EMBEDDING-JOB-004` |
| Path Attempt가 현재 Claim의 Attempt가 아니거나 `STARTED`가 아님 | 409 | 신규 `EMBEDDING-JOB-006` |
| Version 상태에서 Chunking 불가 | 409 | 신규 `DOCUMENT-VERSION-005` |
| Chunk Unique 또는 DB 무결성 충돌 | 409 | `COMMON-008` |
| 지원하지 않는 Document Type·Content Type | 422 | 신규 `DOCUMENT-PARSING-001` |
| Canonical Text가 공백뿐임 | 422 | 신규 `DOCUMENT-PARSING-002` |
| UTF-8 Decode 실패 | 422 | 신규 `DOCUMENT-PARSING-003` |
| MinIO 연결·읽기 장애 | 503 | 기존 `DOCUMENT-STORAGE-001` |
| PROCESSING Job 소유권 필드 불완전 | 500 | `EMBEDDING-JOB-005` |
| Version·FileObject 연관 누락 | 500 | 신규 `DOCUMENT-PARSING-004` |
| Version 상태와 Chunk 데이터 불일치 | 500 | 신규 `DOCUMENT-CHUNK-001` |

권장 Enum 의미는 다음과 같다.

- `EMBEDDING_JOB_ATTEMPT_INVALID`: 요청 Attempt가 현재 Claim 실행 Context가 아님
- `DOCUMENT_VERSION_CHUNKING_NOT_ALLOWED`: 현재 Version 상태에서 Chunk 생성 불가
- `UNSUPPORTED_DOCUMENT_TYPE`: 저장된 Type과 Content Type 조합 미지원
- `DOCUMENT_CONTENT_EMPTY`: 파싱 후 검색할 Text 없음
- `DOCUMENT_TEXT_DECODING_FAILED`: 원본을 엄격한 UTF-8로 해석할 수 없음
- `DOCUMENT_FILE_REFERENCE_MISSING`: Job Version의 파일 연관 또는 저장 위치 누락
- `DOCUMENT_CHUNKS_INCONSISTENT`: Version 상태와 기존 Chunk 데이터가 모순됨
- `FILE_OBJECT_NOT_FOUND`: 기록된 MinIO Object가 실제로 없음

오류 응답에는 현재 Worker ID, 실제 Claim Token, Lease 내부값, Bucket, Object Key, 파일 본문, Chunk
본문을 포함하지 않는다.

## 11. 트랜잭션·동시성·멱등성

### 11.1 첫 번째 Transaction

포함되는 작업은 다음과 같다.

1. Job 쓰기 잠금 조회
2. 잠금 후 현재 시각 계산
3. Job 소유권과 Lease 검증
4. 현재 Claim Attempt 검증
5. Version 쓰기 잠금 조회
6. 형식·FileObject·상태·기존 Chunk 검증
7. 필요한 경우 `PARSING` 전환
8. 필요한 경우 `PARSE_STARTED` 이벤트 저장
9. File Snapshot 또는 기존 `CHUNKED` 결과 반환
10. Flush와 Commit

MinIO 호출, Byte Decode, Chunk 계산은 포함하지 않는다.

### 11.2 Transaction이 없는 구간

포함되는 작업은 다음과 같다.

1. MinIO GetObject와 Byte 읽기
2. UTF-8 Decode와 정규화
3. Chunk 경계와 Text 계산
4. Token 추정과 SHA-256 계산

JPA Entity를 이 구간으로 전달하지 않는다. 첫 Transaction에서 만든 ID와 File 위치 Snapshot만
전달한다.

### 11.3 두 번째 Transaction

포함되는 작업은 다음과 같다.

1. Job 쓰기 잠금 재조회
2. 잠금 후 새 현재 시각 계산
3. Job 소유권과 Lease 재검증
4. 현재 Claim Attempt 재검증
5. Version 쓰기 잠금 재조회와 ID 일치 검증
6. 동시 완료 여부와 기존 Chunk 확인
7. Chunk 전체 Insert와 Flush
8. `CHUNKED` 상태 전환
9. `CHUNKED` 이벤트 저장
10. Response 변환
11. Commit

Controller가 응답을 반환하는 시점은 Service Proxy의 Commit이 끝난 뒤다.

### 11.4 Job과 Version Lock의 역할

Job Lock은 Claim Token 교체, Lease 회수, 같은 Job의 동시 처리와 직렬화 지점이다.

Version Lock은 상태 전이와 Chunk Set 생성을 직렬화하고, 잘못 생성된 중복 Job이 같은 Version을
동시에 처리하더라도 상태를 한 번 더 보호한다.

모든 관련 기능은 Job 다음 Version 순서로 잠근다.

### 11.5 멱등성

- `UPLOADED`에서 `PARSING` 전환은 한 번만 일어난다.
- `PARSE_STARTED`는 상태 전환에 종속되어 한 번만 저장된다.
- `PARSING` 재개는 외부 작업을 다시 수행할 수 있지만 DB 결과를 덮어쓰지 않는다.
- `CHUNKED` 상태의 유효한 재호출은 기존 Chunk 수를 반환한다.
- 동시 후발 완료는 Version Lock 뒤 `CHUNKED`를 보고 재생으로 전환한다.
- Unique Constraint는 Service 경로를 우회한 중복 Insert의 최종 방어선이다.

별도 Idempotency-Key Header나 요청 이력 테이블은 추가하지 않는다. 현재 Job Claim Token과 Attempt ID,
Version 상태, Chunk Unique Constraint의 조합으로 충분하다.

### 11.6 실패 지점

| 실패 지점 | DB 결과 |
| --- | --- |
| 입력·Security 실패 | Transaction 시작 전, 변경 없음 |
| 첫 Transaction의 Job·Attempt·상태 검증 실패 | 변경 없음 |
| `PARSING` 또는 `PARSE_STARTED` 저장 실패 | 첫 Transaction 전체 Rollback |
| MinIO·Decode·Parser·Chunker 실패 | Version은 `PARSING`, Chunk 없음 |
| 외부 작업 중 Token 교체·Lease 만료 | 두 번째 Transaction 변경 없음, Version은 `PARSING` |
| Chunk Insert·Flush 실패 | Chunk 전체, `CHUNKED`, 완료 이벤트 Rollback |
| 완료 이벤트 저장·Commit 실패 | Chunk 전체와 Version 상태까지 Rollback |
| Commit 후 HTTP 응답 유실 | 재호출이 기존 `CHUNKED` 결과를 `200`으로 반환 |

자동 DB 재시도와 MinIO 재시도는 추가하지 않는다. 정상 HTTP 재전송은 멱등 흐름으로 수렴하고,
인프라 재시도 정책은 후속 Worker 실행·실패 처리 기능에서 통합한다.

## 12. 기술·설정·외부 연결

### 12.1 기술

| 범주 | 결정 |
| --- | --- |
| Framework | 기존 Spring Boot와 Spring Data JPA |
| Transaction | Spring `@Transactional`을 별도 Transaction Service에 적용 |
| Lock | JPA Pessimistic Write Lock과 PostgreSQL Row Lock |
| Object Storage | 기존 MinIO Java SDK `GetObject` |
| Text Decode | Java 17 Charset Decoder의 엄격한 UTF-8 오류 처리 |
| Unicode | Java Code Point 기준 경계 계산 |
| Hash | Java 17 SHA-256과 소문자 Hex |
| Validation | Jakarta Bean Validation과 Configuration Validation |
| JSON | API는 기존 Jackson 사용, Chunk Metadata 작성은 추가 없음 |
| Markdown Library | 추가 없음 |
| 추가 Production Library | 추가 없음 |

### 12.2 설정

| 범주 | 변경 |
| --- | --- |
| `application.yml` | `document.chunking.chunk-size`, `document.chunking.overlap` 추가 |
| `.env.example` | `DOCUMENT_CHUNK_SIZE`, `DOCUMENT_CHUNK_OVERLAP` 추가 |
| Secret | 추가 없음 |
| DB 설정 | 추가 없음 |
| Hibernate Batch 설정 | 추가 없음 |
| Spring Multipart 설정 | 추가 없음 |
| MinIO Endpoint·Credential·Bucket | 변경 없음 |

기본값으로 동작하므로 환경변수는 선택 사항이다. 운영에서 값을 바꾸면 Chunk 정책 변경으로 간주하고,
동일 배포군의 모든 Worker가 같은 값을 사용해야 한다.

### 12.3 외부 연결

| 시스템 | 변경 |
| --- | --- |
| PostgreSQL/OpenSQL | 기존 테이블과 Transaction 경로 사용, Schema 변경 없음 |
| MinIO | 기존 연결에 Read 동작 추가 |
| Embedding Server | 추가 없음 |
| Message Broker | 추가 없음 |
| Cache | 추가 없음 |
| 외부 Markdown Parser | 추가 없음 |

### 12.4 Timeout과 Retry

| 항목 | 결정 |
| --- | --- |
| MinIO 연결·읽기 Timeout | 기존 MinIO Client 설정 유지, 추가 없음 |
| MinIO 자동 Retry | 추가 없음 |
| DB Lock Timeout | 기존 Datasource·DB 설정 유지, 추가 없음 |
| Application Retry | 추가 없음 |
| Lease 연장 | 추가 없음 |

현재 기본 Lease는 5분이다. 두 번째 Transaction이 Lease를 재검증하므로, 파일 읽기와 Chunk 계산이
Lease보다 오래 걸리면 저장을 거부한다. Lease 연장과 장시간 작업 지원은 후속 Worker 기능에서 다룬다.

### 12.5 Health Check와 배포

| 항목 | 변경 |
| --- | --- |
| Actuator Health Indicator | 추가 없음 |
| MinIO 전용 Health Endpoint | 추가 없음 |
| Docker Compose | 추가 없음 |
| GitHub Actions | 추가 없음 |
| Gradle Task | 추가 없음 |

기존 `minioIntegrationTest` Task로 실제 GetObject 동작을 검증한다.

## 13. 보안과 관측성

### 13.1 인증과 권한

기존 `/admin/**` ADMIN 정책이 신규 Endpoint를 자동으로 보호하므로 `SecurityConfig`는 수정하지 않는다.

ADMIN 인증은 호출 권한을 증명하고, Worker ID·Claim Token·Attempt ID 검증은 현재 실행 소유권을
증명한다. Body Worker ID가 Machine Credential과 직접 바인딩되지 않는 기존 한계는 유지된다.

### 13.2 민감 정보

- Claim Token은 Body에서만 받는다.
- Token을 Path, Query, Response, 일반 로그, 이벤트 메시지, Metric Label에 넣지 않는다.
- MinIO Access Key와 Secret Key 설정을 변경하거나 노출하지 않는다.
- 파일 본문과 Chunk 본문을 성공·오류 로그에 기록하지 않는다.
- 오류 응답에 Bucket과 Object Key를 넣지 않는다.
- 일반 사용자 API에 내부 Job, Attempt, Chunk 생성 정보를 노출하지 않는다.

### 13.3 로그

성공 요청마다 INFO 로그를 남겨 Worker 처리량만큼 로그를 증가시키지 않는다.

다음 정보만 오류 분석에 사용한다.

- Job ID
- Attempt ID
- Version ID
- Worker ID
- 오류 코드와 처리 단계
- 필요한 경우 Bucket과 Object Key를 Server 오류 로그에만 기록

Claim Token과 파일 내용은 어떤 수준의 로그에도 기록하지 않는다. 멱등 재생은 장애가 아니므로 WARN으로
남기지 않고 필요할 때 DEBUG에서 ID만 기록한다.

### 13.4 이벤트

| 이벤트 | 생성 시점 | 상태 Snapshot | 중복 규칙 |
| --- | --- | --- | --- |
| `PARSE_STARTED` | 최초 `UPLOADED`→`PARSING` Transaction | `UPLOADED`→`PARSING` | 재개 시 추가하지 않음 |
| `CHUNKED` | Chunk 전체 저장과 `PARSING`→`CHUNKED` Transaction | `PARSING`→`CHUNKED` | 재생 시 추가하지 않음 |

이벤트 Message에는 Version ID와 Chunk 수처럼 비민감 식별·집계 정보만 넣는다. Claim Token과 본문은
넣지 않는다. `metadata_json` 사용은 이번 범위에서 추가하지 않는다.

### 13.5 Metric

신규 Metric Library와 Custom Metric은 추가하지 않는다.

운영 분석은 기존 테이블로 다음을 확인할 수 있다.

- Version별 Chunk 수
- `PARSE_STARTED`와 `CHUNKED` 이벤트 간 처리 시간
- `PARSING`에 오래 머문 Version
- `STARTED` Attempt와 `PARSING` Version의 조합

실패 유형과 처리 단계별 Counter는 후속 실패·이벤트 조회 기능에서 추가한다.

## 14. 테스트 설계

### 14.1 Configuration 테스트

- 기본 Chunk 크기가 1,000이고 Overlap이 200이다.
- Chunk 크기가 0 또는 음수면 Application Context가 시작되지 않는다.
- Overlap이 음수면 시작되지 않는다.
- Overlap이 Chunk 크기와 같거나 크면 시작되지 않는다.
- Overlap 0은 허용된다.

### 14.2 Version Entity 테스트

- `UPLOADED`에서 `PARSING`으로 전환된다.
- `PARSING`에서 `CHUNKED`로 전환된다.
- 다른 상태에서 `markParsing`을 호출하면 거부된다.
- 다른 상태에서 `markChunked`를 호출하면 거부된다.
- 기존 `EMBEDDING`, `INDEXED`, `FAILED` 전환 동작이 회귀하지 않는다.

### 14.3 Text Parser 단위 테스트

- 정상 UTF-8 TXT가 같은 본문으로 Decode된다.
- Markdown Heading, Link, Code Fence 문법이 제거되지 않고 보존된다.
- 선두 UTF-8 BOM 한 개가 제거된다.
- CRLF와 단독 CR이 LF로 통일된다.
- 앞뒤 공백과 빈 줄은 보존된다.
- 0 Byte와 공백뿐인 Text는 빈 문서 오류다.
- 잘못된 UTF-8 Byte Sequence는 대체 문자로 숨기지 않고 Decode 오류다.
- 한글, Emoji, 결합 문자가 손실되지 않는다.

### 14.4 Fixed Size Chunker 단위 테스트

- 원문이 Chunk 크기보다 짧으면 Index 0의 Chunk 한 개다.
- 원문 길이가 Chunk 크기와 같으면 Chunk 한 개이고 추가 Overlap Chunk가 없다.
- 원문이 한 Code Point 길면 두 번째 Chunk가 정확한 Overlap에서 시작한다.
- 여러 Chunk의 Index가 0부터 연속된다.
- `char_start`는 포함, `char_end`는 미포함 범위다.
- 인접 Chunk가 설정된 Code Point 수만큼 겹친다.
- Emoji Surrogate Pair 중간에서 분할되지 않는다.
- 같은 Canonical Text와 설정은 동일한 Draft 목록을 만든다.
- 같은 Chunk Text는 동일한 SHA-256 Hash를 만든다.
- 한 글자 차이는 다른 Hash를 만든다.
- 공백 기반 Token 추정치가 결정적이며 음수가 아니다.
- 마지막 Chunk가 원문 끝에 도달하면 Overlap 전용 Chunk를 추가하지 않는다.

### 14.5 공통 소유권 Validator 테스트

- 정상 `PROCESSING` Job의 Worker, Token, 미래 Lease는 통과한다.
- PENDING, INDEXED, FAILED Job은 거부된다.
- Worker, Token, Lock 시각, Lease 중 하나라도 없으면 불변식 오류다.
- 다른 Worker와 과거 Token은 거부된다.
- Lease 만료 시각과 같은 순간부터 만료다.
- 기존 Attempt 시작 Service가 같은 오류 코드와 검증 결과를 유지한다.

### 14.6 Transaction Service 단위 테스트

준비 단계:

- Job을 먼저 잠그고 잠금 후 Clock을 읽는다.
- 현재 Claim Attempt ID, Worker, `STARTED` 상태를 검증한다.
- Job의 Version을 잠그고 currentVersion을 조회하지 않는다.
- TXT와 Markdown의 허용 Content Type을 통과시킨다.
- 다른 Document Type과 Content Type은 MinIO 호출 전에 거부한다.
- FileObject 연관 또는 Bucket·Object Key 누락은 불변식 오류다.
- `UPLOADED`는 `PARSING`과 `PARSE_STARTED`를 함께 저장한다.
- `PARSING` 재개는 이벤트를 추가하지 않는다.
- `CHUNKED` 재생은 기존 Chunk 수를 반환한다.
- 상태와 Chunk 존재가 모순되면 서버 오류다.

완료 단계:

- Job과 Attempt 소유권을 다시 검증한다.
- 준비 단계와 같은 Version인지 검증한다.
- `PARSING`에서만 Chunk를 저장하고 `CHUNKED`로 전환한다.
- Draft 전체를 같은 Version의 Entity로 변환한다.
- Chunk 저장, 상태 전환, 이벤트 저장이 한 Transaction 호출에 포함된다.
- 이미 `CHUNKED`면 저장하지 않고 기존 수를 반환한다.
- Lease 만료와 Token 교체는 `saveAllAndFlush` 전에 거부한다.
- 저장 또는 이벤트 오류가 밖으로 전달되어 Transaction Rollback 대상이 된다.

### 14.7 Orchestration Service 단위 테스트

- 첫 Transaction, Storage Read, Parser, Chunker, 두 번째 Transaction 순서로 호출한다.
- Storage, Parser, Chunker 호출 시 실제 DB Transaction이 활성화되지 않는다.
- 첫 단계가 `CHUNKED` 재생을 반환하면 Storage와 Parser를 호출하지 않는다.
- Storage Read 실패 시 Parser와 두 번째 Transaction을 호출하지 않는다.
- Parser 실패 시 Chunker와 두 번째 Transaction을 호출하지 않는다.
- 빈 Draft 목록은 두 번째 Transaction에 전달하지 않는다.
- 생성 여부와 Response를 Controller에 그대로 전달한다.

### 14.8 Storage 단위 테스트

- 저장된 Bucket과 Object Key로 `GetObject`를 호출한다.
- 성공 시 모든 Byte를 반환하고 MinIO Stream을 닫는다.
- MinIO Object 없음은 `FILE_OBJECT_NOT_FOUND`로 변환한다.
- 연결, 인증, Timeout, 그 밖의 SDK 오류는 `FILE_STORAGE_FAILED`로 변환한다.
- 로그와 예외 Message에 파일 내용이나 Credential이 없다.
- 기존 Store와 Delete 테스트가 회귀하지 않는다.

### 14.9 Controller 테스트

- ADMIN의 최초 성공은 `201`과 정의된 Response를 반환한다.
- ADMIN의 재생 성공은 `200`과 같은 Response 형태를 반환한다.
- 양수가 아닌 Job ID, Attempt ID, Worker ID는 `400`이다.
- Token 누락, 공백, 길이 초과, 잘못된 UUID는 `400`이다.
- Job, Attempt, Version 상태, Parser, Storage, Chunk 불변식 오류가 정의한 HTTP와 코드로 매핑된다.
- 일반 사용자와 미인증 사용자는 기존 Security 정책에 따라 `403`이다.
- Swagger Method, Path, Content Type, 상태 설명이 실제 계약과 같다.

### 14.10 OpenSQL 통합 테스트

- TXT Object가 여러 결정적 Chunk와 `CHUNKED` Version을 만든다.
- Markdown 원문이 보존된 여러 Chunk를 만든다.
- 저장 Row의 Version ID, Index, Text, Token 수, 범위, null Page·Section·Metadata, Hash가 정확하다.
- `PARSE_STARTED`와 `CHUNKED` 이벤트가 한 건씩 생긴다.
- Job은 `PROCESSING`, Attempt는 `STARTED`, Document 상태는 변하지 않는다.
- 후속 Version Job이 `Document.currentVersion`이 아닌 Job Version을 처리한다.
- Chunk 중 하나의 Insert 실패를 유도하면 Chunk가 한 건도 남지 않고 Version은 `PARSING`,
  `CHUNKED` 이벤트는 없다.
- `CHUNKED` 재호출은 Row와 이벤트를 늘리지 않는다.
- 첫 Transaction 후 Token을 교체하거나 Lease를 만료시키면 Chunk가 저장되지 않는다.
- 기존 Unique Constraint가 같은 Version·Index 직접 중복 Insert를 차단한다.

### 14.11 동시성 통합 테스트

Test 메서드 자체에 Transaction을 두지 않고 서로 다른 Thread가 실제 Service Proxy의 독립
Transaction을 사용하게 한다.

- 같은 Job, Attempt, Worker, Token으로 두 요청을 동시에 실행한다.
- Storage Stub의 Barrier로 두 요청이 외부 작업 구간에 함께 진입하게 한다.
- DB에는 결정적인 Chunk Set 한 개만 존재한다.
- `PARSE_STARTED`와 `CHUNKED` 이벤트가 각각 한 건이다.
- 한 결과는 생성, 다른 결과는 재생이다.
- 두 결과의 Job, Attempt, Version, Chunk 수가 같다.
- Timeout, Deadlock, DataIntegrityViolation이 발생하지 않는다.
- 완료 직전 Claim Token을 바꾸면 과거 요청은 저장하지 못한다.

### 14.12 실제 MinIO 통합 테스트

- 실제 Bucket에 TXT Byte를 Store한 뒤 Storage Read가 같은 Byte를 반환한다.
- 다른 Bucket 값이 전달되면 전역 기본 Bucket 대신 전달된 Bucket을 사용한다.
- 없는 Object는 정의된 Not Found 오류다.
- 실제 Stream과 Connection Resource가 정상 종료된다.
- 기존 `minioIntegrationTest` Task에서 분리 실행된다.

### 14.13 회귀 테스트

- Document 최초 업로드와 후속 Version 업로드
- FileObject Hash Dedup과 동시 업로드
- Embedding Job Claim과 동시 Claim
- Embedding Job Attempt 최초 생성·멱등 재생·동시 생성
- 문서 상태 조회
- 전체 기본 Test
- 실제 OpenSQL 통합 Test
- 실제 MinIO 통합 Test

## 15. 구현 순서

### 15.1 안전한 구현 단계

1. 선행 Attempt 시작 변경이 `develop`에 병합됐는지 확인한다.
2. 실제 Issue를 만든 뒤 `feature/{issueNumber}` 형식으로 구현 Branch를 만든다.
3. 공통 Job 소유권 Validator를 추가하고 Attempt 시작 Service가 이를 사용하도록 바꾼다.
4. 기존 Attempt 오류 계약과 Test가 그대로 통과하는지 확인한다.
5. Chunk 설정과 Validation을 추가한다.
6. Storage Read 계약과 MinIO 구현을 추가하고 기존 Test Wrapper를 보강한다.
7. UTF-8 Text Parser와 Parser 단위 테스트를 작성한다.
8. Code Point 기반 `DocumentChunkDraft`와 Fixed Size Chunker 및 단위 테스트를 작성한다.
9. Version Lock과 `DocumentChunkRepository`를 추가한다.
10. `DocumentVersion` 상태 전이 Guard와 Entity 테스트를 추가한다.
11. 첫 번째·두 번째 짧은 Transaction을 `DocumentChunkTransactionService`에 구현한다.
12. Transaction 밖 Orchestration을 `DocumentParsingService`에 구현한다.
13. Request·Response DTO와 관리자 Endpoint를 연결한다.
14. 오류 코드와 Swagger·Controller 테스트를 완성한다.
15. 실제 OpenSQL에서 상태·Chunk·이벤트 원자성과 Rollback을 검증한다.
16. 독립 Thread로 동시 중복 요청과 stale 소유권을 검증한다.
17. 실제 MinIO Read 통합 테스트를 실행한다.
18. 전체 회귀 테스트를 실행하고 실행 결과는 필요할 때 `docs/test-results/`에 별도로 기록한다.

모든 신규 Class, Interface, Record에는 역할·책임·경계를 설명하는 Class 수준 주석을 작성한다.
변경한 파일의 기존 주석은 실제 흐름과 일치하도록 갱신하고, 준비·외부 작업·완료처럼 순차적인 실행
흐름에는 저장소 규칙에 맞는 번호 주석을 사용한다.

### 15.2 권장 Commit 분리

한 Commit에 전체 기능을 넣지 않고 Production, 대응 Test, 통합 검증과 문서를 다음 경계로 분리한다.

1. `refactor: Embedding Job 소유권 검증기 추가`
2. `test: Embedding Job 소유권 검증 계약 고정`
3. `refactor: Attempt 시작에 공통 소유권 검증 적용`
4. `feat: 결정적 텍스트 파싱과 Chunk 계산 구현`
5. `test: 텍스트 파싱과 Chunk 계산 단위 테스트`
6. `feat: MinIO 원본 파일 읽기 지원`
7. `test: MinIO 원본 읽기 단위 테스트`
8. `feat: Document Version 상태와 Chunk 저장소 구성`
9. `test: Document Version Chunk 상태 전이 검증`
10. `feat: Chunk 저장 Transaction과 관리자 API 연결`
11. `test: Chunk 저장 API와 동시성 통합 검증`
12. `docs: 텍스트 파싱 및 Chunk 저장 설계 반영`

Production과 Test를 별도 Commit으로 두어 구현 구조와 검증 계약을 각각 리뷰할 수 있게 한다.
실제 OpenSQL 동시성, MinIO 연결과 Controller 계약은 하나의 통합 검증 Commit으로 묶고,
설계 문서는 최종 구현과 기준선을 반영하는 마지막 Commit으로 유지한다.

## 16. 완료 기준

- 선행 Attempt 시작 계약이 병합된 기준선에서 구현된다.
- 유효한 현재 Job·Attempt·Worker·Claim Token·Lease만 Chunking을 수행할 수 있다.
- 처리 대상은 Job의 Version이며 `Document.currentVersion`을 잘못 사용하지 않는다.
- TXT와 Markdown Object를 FileObject의 Bucket과 Object Key로 MinIO에서 읽는다.
- MinIO Stream은 Storage Service 내부에서 닫힌다.
- UTF-8 오류를 대체 문자로 숨기지 않는다.
- BOM과 줄바꿈 정규화가 문서화된 규칙과 일치한다.
- Markdown 원문을 보존한다.
- 공백뿐인 문서는 명확한 Parser 오류다.
- 기본 1,000 Code Point와 200 Overlap으로 Chunk가 생성된다.
- Unicode 문자를 중간에서 자르지 않는다.
- Chunk Index와 범위가 0 기반, 연속, 결정적이다.
- 같은 Chunk Text가 같은 SHA-256 Hash를 가진다.
- 모든 신규 Chunk에 비어 있지 않은 Content Hash가 저장된다.
- Chunk 전체가 한 Version에 연결된다.
- TXT와 Markdown의 Page, Section, Metadata는 null이다.
- 첫 외부 I/O 전에 Version이 `PARSING`이고 `PARSE_STARTED`가 한 번 기록된다.
- MinIO와 Parser·Chunker는 DB Transaction 밖에서 동작한다.
- 외부 작업 후 소유권과 Lease를 다시 검증한다.
- Chunk 전체, `CHUNKED`, 완료 이벤트가 한 Transaction으로 Commit된다.
- 저장 실패 시 부분 Chunk와 완료 이벤트가 남지 않는다.
- 응답 유실·순차 재호출·동시 재호출이 Chunk와 이벤트를 중복 생성하지 않는다.
- 오래된 Token, 다른 Worker, 잘못된 Attempt, 만료 Lease는 Chunk를 저장하지 못한다.
- 신규 Endpoint는 ADMIN만 호출할 수 있다.
- Claim Token, Secret, 원문, Chunk 본문이 Response와 로그에 노출되지 않는다.
- 새 Migration과 Production Dependency가 없다.
- 새 설정은 기본값으로 기존 배포와 호환된다.
- 단위, Controller, OpenSQL 통합, 동시성, 실제 MinIO, 전체 회귀 테스트가 통과한다.
- 구현 결과는 기능별 여러 Commit으로 분리된다.

## 17. 후속 작업 호환성

### 17.1 Mock Embedding 생성

후속 Embedding 단계는 다음 계약을 소비한다.

- 현재 Job과 Attempt ID, Worker ID, Claim Token을 같은 실행 Context로 전달한다.
- Job이 직접 가리키는 Version이 `CHUNKED`다.
- Version에 Chunk가 한 건 이상 존재한다.
- Chunk Index는 0부터 연속이므로 Index 순서로 안정적으로 Batch 입력을 만들 수 있다.
- Chunk Text와 Content Hash는 불변이다.
- Job에 고정된 `embedding_model_id`를 사용하며 현재 Active 모델을 다시 선택하지 않는다.
- 외부 Vector 생성 전후에 같은 Job 소유권과 Lease를 다시 검증한다.

후속 단계에서 `DocumentChunkRepository`에 Version별 Chunk Index 오름차순 조회를 추가한다.
이번 기능은 존재·수 조회와 저장에 필요한 메서드만 먼저 둔다.

### 17.2 인덱싱 완료

Chunk 완료만으로 Attempt나 Job을 성공 처리하지 않는다.

Embedding 저장과 최종 상태 전환까지 성공한 후 같은 현재 Claim을 검증하면서 Attempt를 `SUCCESS`,
Job과 Version을 `INDEXED`, 필요할 때 Document와 currentVersion을 함께 갱신해야 한다.

### 17.3 실패와 재시도

이번 기능은 오류 후 Version을 `PARSING`에 남겨 안전한 재개를 허용한다.

후속 실패 기능은 다음을 결정해야 한다.

- 어떤 Storage·Parser 오류가 재시도 가능한지
- Attempt를 언제 `FAILED`로 종료할지
- `PARSE_FAILED` 이벤트에 어떤 비민감 Metadata를 남길지
- Retry 횟수와 다음 실행 시각
- 최종 실패 시 Version과 Document 상태

새 Claim Token의 Attempt도 Chunk가 전혀 없는 `PARSING` Version을 재개할 수 있어야 한다.

### 17.4 Lease 회수와 자동 Worker

자동 Worker는 Claim, Attempt 시작, Chunking 호출의 응답을 실행 Context에 보존해야 한다.

장시간 파일 처리에서는 Lease 연장 기능이 필요할 수 있다. 연장이 도입돼도 두 번째 Transaction의
현재 Token과 Lease 재검증 규칙은 유지한다.

### 17.5 실제 Markdown·다중 포맷 Parser

Markdown AST, PDF, DOCX, HTML을 지원할 때는 Parser별 Canonical Text와 Offset 의미를 먼저 확정해야
한다.

문법 제거로 Text가 바뀌면 원본 Byte Offset과 Canonical Text Offset을 혼동하지 않도록 Metadata 또는
별도 위치 Mapping 계약이 필요하다. Page와 Section을 채울 때도 기존 TXT·Markdown null 계약을 깨지
않는 새 Parser 정책으로 확장한다.

### 17.6 알려진 한계

- 공백 기반 `token_count`는 BGE-M3 실제 Token 수가 아니다.
- Markdown 기호, Link URL, Code Fence가 Chunk Text에 남는다.
- 전체 파일과 전체 Chunk Draft를 메모리에 보유한다.
- `PARSING` 동시 재개는 중복 외부 계산을 허용하고 DB 결과만 한 Set으로 수렴시킨다.
- MinIO와 DB는 분산 Transaction이 아니므로 첫 Transaction 이후 외부 실패 시 `PARSING`이 남는다.
- `content_hash`의 DB `NOT NULL`은 Legacy Row 때문에 적용하지 않는다.
- ADMIN Principal과 Body Worker ID는 Machine Identity로 암호학적으로 바인딩되지 않는다.
- Lease 연장, 자동 실패 기록, Recovery는 아직 없다.

## 구현 기록

- 설계 산출물: `docs/design/text-parsing-chunk-storage.md`
- GitHub 기능명: `텍스트 파싱 및 Chunk 저장`
- GitHub Issue: `#68`
- 구현 Branch: `feature/68`
- 대상 Branch: `develop`
- 외부 연결: 기존 MinIO와 OpenSQL만 사용
- Schema와 Production Dependency: 추가 없음
- 후속 범위: Embedding 생성, Attempt 완료·실패, Lease 연장과 Recovery

구현은 이 문서의 준비 Transaction, 외부 작업, 완료 Transaction 경계를 그대로 따른다.
새 관리자 Endpoint는 최초 Chunk Set 저장과 멱등 재생을 HTTP `201`과 `200`으로 구분하며,
Claim Token, 원문, Chunk 본문과 MinIO 위치를 응답·이벤트·일반 로그에 노출하지 않는다.
