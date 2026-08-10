# Issue #134 RAGOps Dashboard 집계 지표 조회 상세 설계

closes #134

## 1. 배경과 목적

관리자가 DocGrid 시스템 전체 상태(문서·인덱싱 작업·Worker·검색)를 확인할 화면이 없어, 실패 작업이
쌓이거나 Worker가 죽어도 운영자가 즉시 알아챌 방법이 없다. 이번 작업은 RAGOps Dashboard의 첫 조각으로,
관리자가 호출하면 현재 시스템 현황을 하나의 응답으로 집계해서 보여주는 조회 전용 API를 추가한다.

대시보드는 자체 테이블을 소유하지 않는다. `documents`, `document_versions`, `embedding_jobs`,
`worker_nodes`, `search_queries`는 모두 다른 담당자가 소유한 테이블이며, 이번 작업은 그 데이터를
읽기 전용으로 집계만 한다.

WebSocket 실시간 push, 재처리 트리거 API, Worker 상태 전이 이벤트 훅은 이 Issue 범위 밖이며
후속 Issue에서 이 집계 로직을 재사용한다.

### 1.1 성공 기준

- 문서/작업/Worker/검색 4개 카테고리 지표를 정확히 집계해서 반환한다.
- ADMIN 역할만 접근 가능하다.
- 모든 조회가 SELECT 전용이며 A 담당자 소유 데이터에 쓰기가 없다.
- Worker Heartbeat 판정 로직을 새로 만들지 않고 기존 `WorkerNodeQueryService`를 재사용한다.
- 신규 테이블·컬럼이 없어 Flyway Migration이 필요 없다.

## 2. 범위

### 2.1 포함

- `GET /admin/dashboard/summary` API
- `DashboardQueryService` 및 응답 DTO
- `DocumentRepository`, `EmbeddingJobRepository`, `SearchQueryRepository` 집계 쿼리 추가
- 단위 테스트, 평균 처리 시간 Native Query에 대한 PostgreSQL Repository 테스트

### 2.2 제외

- WebSocket 실시간 push (`/topic/dashboard`)
- FAILED 작업 목록 조회 — 기존 `GET /admin/indexing-jobs?status=FAILED` 재사용, 신규 구현 없음
- 관리자 재처리(단건·전체) API
- Worker 상태 전이 시점 이벤트 발행 훅
- Flyway Migration, DB Index 변경
- Dashboard 화면(Frontend)

## 3. API 계약

```text
GET /admin/dashboard/summary
Authorization: Bearer {JWT}
```

- 요청 파라미터 없음
- ADMIN 역할만 호출 가능 (`SecurityConfig`의 `/admin/** -> hasRole("ADMIN")` 재사용, 별도 Security
  설정 추가 없음)
- 응답은 조회 시점 기준 Snapshot이며 캐시하지 않는다

### 3.1 응답 예시

```json
{
  "documents": { "total": 25368, "searchable": 21742, "pendingIndex": 132 },
  "jobs": { "pending": 132, "processing": 8, "failed": 27, "avgProcessMs": 3200 },
  "workers": { "activeCount": 5, "totalCount": 6 },
  "search": { "recent24hCount": 342 }
}
```

### 3.2 필드 정의

| 필드 | 정의 | 비고 |
|---|---|---|
| `documents.total` | `Document.deletedAt IS NULL` 카운트 | Soft-delete 제외 전체 문서 |
| `documents.searchable` | `Document.status = INDEXED` 카운트 | `DocumentIndexingCompletionService.transitionAndRecordEvent()`가 `document.activateIndexedVersion()`으로 같은 Transaction에서 원자적으로 동기화하므로 신뢰 가능 |
| `documents.pendingIndex` | `Document.status IN (UPLOADED, INDEXING)` | `FAILED`는 `jobs.failed`가 별도로 이미 노출하므로 포함하지 않음 |
| `jobs.pending` / `processing` / `failed` | `EmbeddingJobRepository.countByStatus(...)` | 상태별 단순 카운트 |
| `jobs.avgProcessMs` | `AVG(completed_at - started_at)` (ms) | Queue 대기 시간(`created_at`)은 제외한 순수 처리 시간. 완료 Job이 없으면 `null` |
| `workers.activeCount` | `WorkerNodeQueryService.getWorkers()` 결과 중 `status IN (ACTIVE, IDLE)` | Heartbeat 판정 로직 재사용, 재구현 없음 |
| `workers.totalCount` | `WorkerNodeQueryService.getWorkers()` 결과 전체 개수 | |
| `search.recent24hCount` | `SearchQueryRepository.countByCreatedAtAfter(now - 24h)` | |

## 4. 조회 구조

### 4.1 Repository

- `DocumentRepository`: `countByDeletedAtIsNull()`, `countByStatus(DocumentStatus)`,
  `countByStatusIn(Collection<DocumentStatus>)` — 메서드 이름 기반 자동 쿼리
- `EmbeddingJobRepository`: `countByStatus(EmbeddingJobStatus)`, `findAllByStatus(EmbeddingJobStatus)`
  (후속 전체 재처리 Issue에서 재사용 예정), `findAverageProcessingMillis()` — PostgreSQL
  `EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000` Native Query, 완료 Job이 없으면 `null` 반환
- `SearchQueryRepository`: `countByCreatedAtAfter(LocalDateTime)`

### 4.2 DashboardQueryService

`@Transactional(readOnly = true)` 클래스이며 자체 Repository 3개와 `WorkerNodeQueryService`에
의존한다. 각 카테고리를 독립적으로 조회해 `DashboardSummaryResponse`로 조합한다.

- Worker 집계는 `resolveEffectiveStatus()`를 다시 구현하지 않고 `WorkerNodeQueryService.getWorkers()`
  호출 결과의 `status` 필드(이미 Heartbeat 기준으로 계산됨)를 그대로 센다. Heartbeat 판정 기준이
  바뀌어도 이 Service는 수정할 필요가 없다.
- `avgProcessMs`는 Native Query가 `null`을 반환하면 그대로 `null`을 응답하고, 값이 있으면 반올림해
  `Long`으로 변환한다. 0으로 기본값을 채우지 않는다 — 완료 Job이 없는 상태에서 "평균 0ms"는 사실과
  다른 정보이기 때문이다.
- 최근 24시간 기준 시각은 주입받은 `Clock`으로 계산해 테스트 시 고정 가능하게 한다.

### 4.3 DTO

`DashboardSummaryResponse`가 `DocumentsSummaryResponse` / `JobsSummaryResponse` /
`WorkersSummaryResponse` / `SearchSummaryResponse` 4개를 필드로 갖는다. 각 필드는 `@Schema`로
Swagger 설명을 붙인다.

## 5. 오류 계약

| 상황 | HTTP | 처리 |
|---|---:|---|
| 미인증 또는 ADMIN 아님 | 403 | 기존 `SecurityConfig`의 `/admin/**` 정책 |

요청 파라미터가 없어 입력 검증 오류 케이스는 없다.

## 6. 테스트 설계

### 6.1 단위 테스트 (`DashboardQueryServiceTest`)

- 4개 카테고리 지표가 각 Repository/Service 응답으로부터 정확히 조합되는지
- 완료 Job이 없어 평균 처리 시간이 없을 때 `avgProcessMs`가 `null`인지
- `STOPPED`·`DEAD` Worker가 `activeCount`에서 제외되는지

### 6.2 PostgreSQL Repository 테스트 (`EmbeddingJobDashboardRepositoryTest`, `@DataJpaTest`)

- `created_at`이 `started_at`보다 훨씬 이전이어도 평균 계산이 대기 시간을 섞지 않는지
- 완료 Job이 없으면 `null`을 반환하는지
- 여러 완료 Job의 처리 시간이 올바르게 평균나는지

### 6.3 범위에서 제외한 테스트

Controller에는 요청 파라미터가 없고 권한 정책은 `SecurityConfig` 레벨에서 이미 다른 `/admin/**`
API들로 검증되므로, 별도 Controller 계층 테스트는 추가하지 않았다.

## 7. 커밋 분할

1. `docs: #134 RAGOps Dashboard 집계 설계 문서 추가`
2. `feat: #134 대시보드 집계용 Repository 쿼리 추가`
3. `feat: #134 대시보드 집계 지표 DTO 및 Query Service 구현`
4. `feat: #134 대시보드 집계 지표 조회 Controller 구현`
5. `test: #134 대시보드 집계 지표 단위·Repository 테스트 추가`

## 8. 완료 조건

- `GET /admin/dashboard/summary` 호출 시 문서/작업/Worker/검색 4개 카테고리 지표가 정확히 반환된다
- ADMIN이 아닌 사용자 접근 시 403
- 모든 쿼리가 SELECT 전용이며 Flyway Migration 변경이 없다
- 단위 테스트와 PostgreSQL Repository 테스트가 통과한다
- 전체 빌드(`./gradlew build`)가 회귀 없이 통과한다
