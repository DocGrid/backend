# #103 PDF·DOCX 문서 파싱 지원 추가 구현 검증 결과

## 1. 검증 목적

텍스트 PDF와 DOCX가 기존 Worker 문서 파싱 경계에 연결되고, PDF Page와 DOCX Section Metadata가
PostgreSQL Chunk Row까지 보존되는지 검증한다. OCR은 실행하지 않으며 검색 가능한 Text가 없는 PDF가
일반 손상 문서와 구분되는지도 확인한다.

## 2. 실행 환경

- 실행일: 2026-08-06
- Java: 17
- Spring Boot: 3.5.16
- PostgreSQL: 17.8
- pgvector: 0.8.1
- PDF Parser: Apache PDFBox 3.0.8
- DOCX Parser: Apache POI OOXML 5.5.1
- Database Container: `pgvector/pgvector:0.8.1-pg17`, healthy

Database Version은 다음 읽기 전용 Query로 확인했다.

```sql
SELECT current_setting('server_version'), extversion
FROM pg_extension
WHERE extname = 'vector';
```

결과:

```text
17.8 (Debian 17.8-1.pgdg12+1) | 0.8.1
```

## 3. 업로드·Parser 계약 검증

### 3.1 업로드

검증한 조합:

| Extension | Content-Type | 결과 |
| --- | --- | --- |
| `pdf` | `application/pdf` | 허용 |
| `docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | 허용 |
| `pdf` | `application/octet-stream` | 거부 |
| `docx` | `application/pdf` | 거부 |
| `doc` | `application/msword` | 거부 |

TXT·Markdown의 기존 확장자와 Content-Type 조합도 단위 테스트로 함께 확인했다.

### 3.2 PDF

메모리 PDF Fixture로 다음 계약을 검증했다.

- 두 Page의 Text와 1-based Page Number 보존
- Text가 없는 Page를 건너뛰고 다음 Page의 원래 번호 보존
- 전체 Page에 검색 가능한 Text가 없으면 `DOCUMENT-PARSING-006`
- Password 보호 PDF면 `DOCUMENT-PARSING-005`
- 손상된 PDF면 `DOCUMENT-PARSING-007`

### 3.3 DOCX

메모리 DOCX Fixture로 다음 계약을 검증했다.

- Heading 이전 본문 보존
- Heading Text를 Section 첫 줄과 Section Title로 보존
- Paragraph와 Table을 Body 원래 순서대로 추출
- Table Cell은 Tab, Row는 LF로 구분
- 빈 DOCX면 `DOCUMENT-PARSING-002`
- 손상된 DOCX면 `DOCUMENT-PARSING-007`

## 4. Segment Chunk 검증

`FixedSizeChunker`의 기존 Unicode Code Point, Overlap, Token 추정과 SHA-256 계약을 유지하면서 다음을
추가로 확인했다.

- PDF Page와 DOCX Section 경계를 넘는 Chunk 없음
- 문서 전체에서 `chunk_index`가 0부터 연속
- Segment 사이 LF 한 Code Point를 포함한 전역 `char_start`, `char_end`
- `page_no`, `section_title`, `metadata_json` 복사
- Page Number 양수와 Section Title 500자 저장 계약 검증

## 5. PostgreSQL 17 통합 검증

실행 명령:

```bash
JWT_SECRET=<ephemeral-test-value> \
DB_SSLMODE=disable \
MINIO_ENDPOINT=<local-test-endpoint> \
MINIO_ACCESS_KEY=<local-test-value> \
MINIO_SECRET_KEY=<local-test-value> \
MINIO_BUCKET=<local-test-bucket> \
./gradlew test --tests '*DocumentChunkingIntegrationTest'
```

결과:

```text
5 tests, 0 failed
BUILD SUCCESSFUL
```

추가된 통합 시나리오:

1. 두 Page PDF를 Chunk 2개로 저장하고 `page_no = 1, 2`와 전역 Offset `0..4`, `5..9` 확인
2. 두 Section DOCX를 Chunk 2개로 저장하고 `section_title = A, D`와 전역 Offset 확인
3. Text가 없는 PDF가 OCR 필요 오류로 종료될 때 Chunk 0건과 `CHUNKED` Event 0건 확인

기존 TXT 원자 저장·순차 재생과 두 동시 요청의 단일 Chunk Set 수렴 테스트도 함께 통과했다.

## 6. 전체 회귀 테스트

로컬 설정 파일에 비밀 값을 추가하지 않고 실행 Process에만 임시 Test 값을 주입했다.

```bash
JWT_SECRET=<ephemeral-test-value> \
DB_SSLMODE=disable \
MINIO_ENDPOINT=<local-test-endpoint> \
MINIO_ACCESS_KEY=<local-test-value> \
MINIO_SECRET_KEY=<local-test-value> \
MINIO_BUCKET=<local-test-bucket> \
./gradlew test
```

최종 결과:

```text
607 tests, 0 failed, 0 errors, 0 skipped
BUILD SUCCESSFUL in 14s
```

## 7. 실행 중 확인한 환경 오류

### 7.1 SSL Mode 불일치

최초 전체 Test 실행은 Local 설정이 SSL을 요구하지만 `pgvector/pgvector` 개발 Container가 SSL을
제공하지 않아 실패했다.

```text
The server does not support SSL.
```

제품 코드나 `.env`를 바꾸지 않고 검증 Process에만 `DB_SSLMODE=disable`을 적용했다.

### 7.2 Test JWT 설정 누락

SSL 보정 후 일부 Spring Context Test는 Test용 `JWT_SECRET` 미주입으로 실패했다.

```text
Could not resolve placeholder 'JWT_SECRET'
```

Repository 설정에 Secret을 기록하지 않고 일회성 Test 값을 Process 환경에 주입했다. 두 실패는
PDF·DOCX Parser 또는 PostgreSQL Schema 회귀가 아니며, 설정 보정 후 전체 Test가 통과했다.

## 8. OCR 판정

이번 구현은 OCR Engine을 포함하지 않는다.

- Text Layer가 있는 PDF: PDFBox로 직접 처리
- Text Layer가 없지만 Page가 있는 PDF: `DOCUMENT_OCR_REQUIRED`
- OCR, 이미지 전처리와 언어 Pack: 후속 선택 작업

따라서 Open Source 대회 제출물은 PDFBox·POI 기반의 순수 Java Parsing만으로 재현 가능하다. OCR이
실제 요구사항으로 확정되면 Tesseract를 별도 Process 또는 Container Adapter로 추가하고 Language Pack과
Native Runtime을 독립 배포하는 방식이 적합하다.

## 9. 최종 판정

| 완료 조건 | 결과 |
| --- | --- |
| PDF·DOCX 업로드 조합 | 통과 |
| PDF Page별 Text·Page Number | 통과 |
| DOCX Heading·본문·Table·Section Title | 통과 |
| 암호화·OCR 필요·손상·빈 문서 오류 | 통과 |
| TXT·Markdown 회귀 | 통과 |
| Segment 경계·전역 Offset·Metadata | 통과 |
| 파싱 실패 시 부분 Chunk 없음 | 통과 |
| Worker Parsing Pipeline 연결 | 통과 |
| PostgreSQL 17 통합 테스트 | 통과 |
| 전체 Gradle 회귀 | 통과 |

실제 BGE-M3 호출부터 `vector(1024)` 저장까지의 PDF·DOCX 전체 관통 E2E와 공식 OpenSQL 17.8 원격
검증은 별도 후속 검증 범위다.
