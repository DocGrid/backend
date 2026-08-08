# Issue #119 관리자 인덱싱 Job·Attempt·Event 조회 상세 설계

closes #119

## 1. 배경과 목적

현재 관리자 API는 Worker 목록 조회와 인덱싱 Job의 Claim·Lease·Attempt 시작·파싱·임베딩·완료·실패·
수동 재처리 같은 제어 명령을 제공한다. 그러나 운영자가 Job의 현재 상태와 과거 Attempt, 상태 전이
Event를 조회할 API는 없어 장애 원인과 재처리 대상을 DB에서 직접 확인해야 한다.

이번 작업은 기존 상태 전이와 Worker 실행 경로를 변경하지 않고, 관리자에게 필요한 읽기 전용 관측
API를 추가한다. 기존 `/admin/workers`는 그대로 재사용하며 Job 목록·상세, Attempt 이력, Event
타임라인만 새로 제공한다.

### 1.1 성공 기준

- Job 목록을 상태·문서·현재 소유 Worker 조건으로 필터링한다.
- 모든 다건 응답은 최대 100건의 Pagination을 적용한다.
- Job 상세에서 현재 Queue·Retry·Lease·종결 상태를 확인할 수 있다.
- Attempt와 Event는 각각 독립된 Pagination으로 조회한다.
- Claim Token, 내부 오류 메시지와 Event Metadata를 응답하지 않는다.
- 모든 API는 기존 `/admin/**` ADMIN 권한 정책을 따른다.
- 조회 API가 기존 인덱싱 상태나 감사 이력을 변경하지 않는다.

## 2. 범위

### 2.1 포함

- 관리자 Job 목록 조회
- 관리자 Job 상세 조회
- Job별 Attempt 이력 조회
- Job별 Indexing Event 타임라인 조회
- 공통 페이지 응답 계약
- Query 전용 Repository·Converter·Service
- 단위·Controller·PostgreSQL 통합 테스트

### 2.2 제외

- Worker 목록 API 재구현
- Job 상태 변경 또는 일괄 수동 재처리
- Claim·Retry·Lease 정책 변경
- 로그 수집, Metrics, 알림과 Dashboard UI
- Event Metadata 원문 공개
- Flyway Migration과 DB Index 변경

## 3. API 계약

### 3.1 Job 목록

```text
GET /admin/indexing-jobs
  ?status=PENDING
  &documentId=1
  &workerId=2
  &page=0
  &size=20
```

- 모든 필터는 선택이다.
- `page` 기본값은 0, `size` 기본값은 20이다.
- `page >= 0`, `1 <= size <= 100`을 검증한다.
- 정렬은 `created_at DESC, id DESC`로 고정한다.
- Worker 필터는 `locked_by_worker_id`인 현재 소유 Worker를 의미한다.

목록 항목은 다음 정보를 포함한다.

- Job ID, 상태, 우선순위, Retry Count, Max Retry Count, Next Retry At
- Document ID와 제목
- Version ID와 Version 번호·상태
- Embedding Model ID와 이름
- 현재 Worker ID와 이름
- 공개 가능한 Error Code
- 생성·시작·완료·실패 시각

### 3.2 Job 상세

```text
GET /admin/indexing-jobs/{jobId}
```

목록 정보에 `locked_at`, `lock_expires_at`을 추가한다. Claim Token과 Error Message는 반환하지 않는다.

### 3.3 Attempt 이력

```text
GET /admin/indexing-jobs/{jobId}/attempts?page=0&size=20
```

- 정렬은 `attempt_no DESC, id DESC`로 고정한다.
- Attempt ID·번호·상태, Worker ID·이름, 시작·종료·소요 시간, Error Code를 반환한다.
- Claim Token과 Error Message는 반환하지 않는다.
- Attempt가 없어도 Job이 존재하면 빈 Page를 반환한다.

### 3.4 Event 타임라인

```text
GET /admin/indexing-jobs/{jobId}/events?page=0&size=20
```

- 정렬은 `occurred_at DESC, id DESC`로 고정한다.
- Event ID·Type, From/To 상태, 고정 메시지, 발생 시각을 반환한다.
- `metadata_json`은 Worker·Attempt 식별자 외에 실패 진단 Snapshot을 포함할 수 있으므로 공개하지 않는다.
- Event가 없어도 Job이 존재하면 빈 Page를 반환한다.

## 4. 공통 Pagination 응답

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```

Spring Data `Page`를 Controller에서 직접 직렬화하지 않는다. `PageResponse<T>`가 응답 구조를 고정하고
Entity 대신 이미 변환된 DTO만 보유한다.

## 5. 조회 구조

### 5.1 Job 목록

`EmbeddingJobRepository`에 관리자 조회 전용 JPQL과 Count Query를 추가한다. DocumentVersion,
Document, EmbeddingModel, 현재 Worker를 Fetch Join해 Page 한 건당 추가 Lazy Query가 발생하지 않게 한다.
필터는 Null이면 생략한다.

### 5.2 Job 상세

Job ID로 같은 연관관계를 Fetch Join한다. 없으면 기존 `EMBEDDING_JOB_NOT_FOUND` 오류를 반환한다.

### 5.3 Attempt와 Event

두 Repository는 Job ID 조건과 고정 정렬을 가진 Page Query를 제공한다. Attempt의 Worker는 Fetch
Join하고, Event는 Job 존재를 Query Service에서 먼저 확인한 뒤 이력만 조회한다.

### 5.4 Query Service

`IndexingJobAdminQueryService`는 클래스 수준 `@Transactional(readOnly = true)`를 사용한다.

1. Controller가 검증한 필터와 Pagination 값을 받는다.
2. Repository에서 Entity Page 또는 상세 Snapshot을 조회한다.
3. `IndexingJobAdminConverter`로 공개 DTO를 생성한다.
4. `PageResponse`로 Pagination Metadata를 고정한다.

## 6. 공개 정보 경계

| 저장 필드 | 응답 | 이유 |
|---|---|---|
| Job `claim_token` | 제외 | 현재 소유권 증명 값 |
| Job `error_message` | 제외 | 내부 Provider·Storage 진단 포함 가능 |
| Attempt `claim_token` | 제외 | 과거 소유권 증명 값 |
| Attempt `error_message` | 제외 | 내부 예외 Snapshot 포함 가능 |
| Event `metadata_json` | 제외 | Worker·Attempt와 실패 진단 Metadata 원문 |
| Job·Attempt `error_code` | 포함 | 운영 분류에 필요한 제한된 코드 |
| Event `message` | 포함 | Command 계층에서 생성하는 고정된 안전 메시지 |

응답 DTO에는 제외 필드 자체를 정의하지 않아 Jackson 설정 변경이나 실수로 노출될 가능성을 줄인다.

## 7. 오류 계약

| 상황 | HTTP | 코드 |
|---|---:|---|
| Job 없음 | 404 | `EMBEDDING-JOB-001` |
| `jobId`, `documentId`, `workerId`가 양수가 아님 | 400 | `COMMON-002` |
| `page < 0` | 400 | `COMMON-002` |
| `size < 1` 또는 `size > 100` | 400 | `COMMON-002` |
| 지원하지 않는 Job 상태 | 400 | 기존 Enum 변환 오류 처리 |
| 미인증 또는 ADMIN 아님 | 403 | 기존 Security 정책 |

`SecurityConfig`의 `/admin/** -> hasRole("ADMIN")` 규칙을 재사용하므로 Security 설정은 변경하지 않는다.

## 8. 테스트 설계

### 8.1 단위 테스트

- Job 목록 필터와 Page 값이 Repository로 전달되는지 검증
- Job 상세 DTO 변환과 Not Found 검증
- Attempt·Event 빈 Page와 데이터 Page 변환 검증
- 민감 필드가 Response Record 구성요소에 존재하지 않는지 검증

### 8.2 Controller 테스트

- 네 API의 정상 응답 구조
- 상태·문서·Worker 필터 전달
- Page·Size·양수 ID Validation
- ADMIN 성공, USER·미인증 403
- 직렬화 JSON에 Claim Token, Error Message, Metadata JSON이 없는지 검증

### 8.3 PostgreSQL 통합 테스트

- 상태·문서·Worker 필터 조합
- `created_at DESC, id DESC` 고정 정렬과 목록 Pagination
- Attempt `attempt_no DESC, id DESC` 정렬
- Event `occurred_at DESC, id DESC` 정렬
- 종료 Job처럼 현재 Worker가 Null인 항목 조회

## 9. 커밋 분할

1. `docs: #119 관리자 인덱싱 조회 설계 문서 추가`
2. `feat: #119 관리자 조회 페이지 응답 계약 추가`
3. `feat: #119 Job·Attempt·Event 조회 쿼리 추가`
4. `feat: #119 관리자 인덱싱 조회 Service 구현`
5. `feat: #119 관리자 인덱싱 조회 API 추가`
6. `test: #119 관리자 인덱싱 조회 단위·Controller 테스트 추가`
7. `test: #119 관리자 인덱싱 조회 PostgreSQL 통합 검증 추가`

## 10. 완료 조건

- Job 목록·상세와 Attempt·Event Page 조회 가능
- 상태·문서·Worker 필터 및 고정 정렬 동작
- 민감한 소유권·내부 오류·Metadata 미노출
- 기존 Worker 조회와 인덱싱 Command API 회귀 없음
- 관리자 권한과 입력 검증 통과
- PostgreSQL 통합 테스트와 전체 회귀 테스트 통과
