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

읽는 순서는 Repository(4.1~4.3) → DTO(4.4) → Service(4.5) → Controller(4.6)를 따른다.
Service가 Repository들을 조합해서 DTO를 만들고, Controller는 그 결과를 그대로 HTTP로 감싸기만
한다.

### 4.1 `DocumentRepository` — 문서 카운트 3종

```java
// A담당자 영역 — B담당자는 존재 확인 등 읽기 전용으로만 사용
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * 대시보드 집계 카드의 전체 문서 수. Soft-delete된 문서는 제외한다.
     */
    long countByDeletedAtIsNull();

    /**
     * 대시보드 집계 카드에서 특정 상태 하나에 속하는 문서 수를 센다 (예: 검색 가능 문서 수).
     */
    long countByStatus(DocumentStatus status);

    /**
     * 대시보드 집계 카드에서 여러 상태에 걸친 문서 수를 센다 (예: 인덱싱 대기 중 문서 수).
     */
    long countByStatusIn(Collection<DocumentStatus> statuses);

    // ... 기존 메서드들
}
```

세 메서드 다 `@Query` 없이 메서드 이름만으로 Spring Data JPA가 쿼리를 자동 생성한다
(`countByDeletedAtIsNull` → `WHERE deleted_at IS NULL`, `countByStatusIn` → `WHERE status IN (...)`).
`DocumentRepository`는 파일 상단 주석에 "A담당자 영역 — B담당자는 읽기 전용으로만 사용"이라고
이미 명시돼 있어서, 쓰기 메서드는 추가하지 않고 count류만 붙였다.

### 4.2 `EmbeddingJobRepository` — 상태별 카운트 + 평균 처리 시간

```java
/**
 * 대시보드 집계 카드(대기/처리 중/실패 작업 수)에 사용하는 상태별 Job 수를 센다.
 */
long countByStatus(EmbeddingJobStatus status);

/**
 * 관리자 전체 재처리 대상인 FAILED Job 전체를 조회한다.
 */
List<EmbeddingJob> findAllByStatus(EmbeddingJobStatus status);

/**
 * 완료된 Job의 평균 처리 시간을 밀리초 단위로 계산한다.
 *
 * <p>Queue 대기 시간({@code created_at})은 제외하고 Worker가 실제로 처리한 구간({@code started_at}
 * ~ {@code completed_at})만 반영한다. 완료된 Job이 없으면 {@code null}을 반환한다.
 */
@Query(
    value = """
        SELECT AVG(EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000)
        FROM embedding_jobs
        WHERE status = 'INDEXED'
          AND started_at IS NOT NULL
          AND completed_at IS NOT NULL
        """,
    nativeQuery = true
)
Double findAverageProcessingMillis();
```

`countByStatus`는 4.1과 같은 메서드 이름 자동 쿼리. `findAllByStatus`는 이번 이슈에서 직접
쓰지 않고, 후속 이슈(FAILED 전체 재처리)가 `EmbeddingJobRepository.findAllByStatus(FAILED)`로
재사용할 걸 미리 준비해둔 것이다.

`findAverageProcessingMillis()`는 `AVG(completed_at - started_at)`을 쓰지 `AVG(completed_at -
created_at)`을 쓰지 않는다 — `created_at`부터 재면 Queue에서 대기한 시간까지 "처리 시간"에 섞여서
지표 의미가 흐려진다. PostgreSQL 전용 함수 `EXTRACT(EPOCH FROM ...)`를 쓰기 때문에 JPQL이 아니라
`nativeQuery = true`로 짰다. `AVG`는 대상 행이 0개면 SQL 표준상 `NULL`을 반환하므로, 반환 타입도
기본값 `0.0`이 아니라 `Double`(nullable)로 선언해서 "완료된 Job이 아예 없다"는 사실을 그대로
드러낸다.

### 4.3 `SearchQueryRepository` — 최근 검색 수

```java
public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {

    /**
     * 대시보드 집계 카드의 최근 검색 요청 수. 기준 시각 이후 생성된 검색 Query를 센다.
     */
    long countByCreatedAtAfter(LocalDateTime since);
}
```

원래 이 인터페이스는 `JpaRepository`만 상속하고 메서드가 하나도 없었다. 이번에 처음 추가한
메서드다. `since` 기준 시각(now - 24h)은 4.5절 Service가 계산해서 넘긴다.

### 4.4 DTO — `DashboardSummaryResponse` + 하위 4개

```java
public record DashboardSummaryResponse(
    @Schema(description = "문서 현황")
    DocumentsSummaryResponse documents,

    @Schema(description = "인덱싱 작업 현황")
    JobsSummaryResponse jobs,

    @Schema(description = "Worker 현황")
    WorkersSummaryResponse workers,

    @Schema(description = "검색 현황")
    SearchSummaryResponse search
) { }

public record DocumentsSummaryResponse(
    @Schema(description = "전체 문서 수 (Soft-delete 제외)", example = "25368")
    long total,

    @Schema(description = "검색 가능 문서 수 (INDEXED 상태)", example = "21742")
    long searchable,

    @Schema(description = "인덱싱 대기 중인 문서 수 (UPLOADED, INDEXING 상태)", example = "132")
    long pendingIndex
) { }

public record JobsSummaryResponse(
    @Schema(description = "인덱싱 대기 작업 수 (PENDING)", example = "132")
    long pending,

    @Schema(description = "처리 중인 작업 수 (PROCESSING)", example = "8")
    long processing,

    @Schema(description = "실패 작업 수 (FAILED)", example = "27")
    long failed,

    @Schema(description = "평균 임베딩 처리 시간(ms). Queue 대기 시간은 제외한 순수 처리 시간이며, "
        + "완료된 Job이 없으면 null", example = "3200")
    Long avgProcessMs
) { }

public record WorkersSummaryResponse(
    @Schema(description = "정상(ACTIVE·IDLE) Worker 수", example = "5")
    long activeCount,

    @Schema(description = "전체 등록 Worker 수", example = "6")
    long totalCount
) { }

public record SearchSummaryResponse(
    @Schema(description = "최근 24시간 검색 요청 수", example = "342")
    long recent24hCount
) { }
```

5개 파일로 나눈 이유는 프로젝트 컨벤션(`dto/response`에 응답 DTO 하나당 파일 하나)을 따른 것이다.
`documents`/`jobs`/`workers`/`search`가 각자 독립된 record라, 나중에 특정 카테고리 하나만 API로
따로 빼야 할 일이 생겨도 재사용하기 쉽다. `avgProcessMs`만 `Long`(기본 타입 `long`이 아니라
Wrapper)인 이유는 4.2에서 설명한 `null` 가능성을 DTO까지 그대로 전달하기 위해서다.

### 4.5 `DashboardQueryService`

```java
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class DashboardQueryService {

    private static final List<DocumentStatus> PENDING_INDEX_STATUSES =
        List.of(DocumentStatus.UPLOADED, DocumentStatus.INDEXING);

    private final DocumentRepository documentRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final SearchQueryRepository searchQueryRepository;
    private final WorkerNodeQueryService workerNodeQueryService;
    private final Clock clock;

    public DashboardSummaryResponse getSummary() {
        return new DashboardSummaryResponse(
            getDocumentsSummary(),
            getJobsSummary(),
            getWorkersSummary(),
            getSearchSummary()
        );
    }

    private DocumentsSummaryResponse getDocumentsSummary() {
        return new DocumentsSummaryResponse(
            documentRepository.countByDeletedAtIsNull(),
            documentRepository.countByStatus(DocumentStatus.INDEXED),
            documentRepository.countByStatusIn(PENDING_INDEX_STATUSES)
        );
    }

    private JobsSummaryResponse getJobsSummary() {
        Double averageMillis = embeddingJobRepository.findAverageProcessingMillis();
        return new JobsSummaryResponse(
            embeddingJobRepository.countByStatus(EmbeddingJobStatus.PENDING),
            embeddingJobRepository.countByStatus(EmbeddingJobStatus.PROCESSING),
            embeddingJobRepository.countByStatus(EmbeddingJobStatus.FAILED),
            averageMillis == null ? null : Math.round(averageMillis)
        );
    }

    private WorkersSummaryResponse getWorkersSummary() {
        List<WorkerNodeResponse> workers = workerNodeQueryService.getWorkers();
        long activeCount = workers.stream()
            .filter(worker -> worker.status() == WorkerStatus.ACTIVE || worker.status() == WorkerStatus.IDLE)
            .count();
        return new WorkersSummaryResponse(activeCount, workers.size());
    }

    private SearchSummaryResponse getSearchSummary() {
        LocalDateTime since = LocalDateTime.now(clock).minusHours(24);
        return new SearchSummaryResponse(searchQueryRepository.countByCreatedAtAfter(since));
    }
}
```

- `getSummary()`가 4개의 `private` 메서드를 호출해서 `DashboardSummaryResponse`를 조립하는
  단순 오케스트레이션이다. 카테고리 4개가 서로 의존하지 않아 순서는 상관없다.
- `getDocumentsSummary()`는 4.1의 세 메서드를 그대로 호출한다. `PENDING_INDEX_STATUSES`를
  상수로 뺀 건 `pendingIndex`의 정의(`UPLOADED`, `INDEXING`)가 이 클래스 밖에서도 참조될 일이
  없어서 필드 상수로 충분하다고 판단했다.
- `getJobsSummary()`가 `averageMillis == null ? null : Math.round(averageMillis)`로 널 처리를
  명시적으로 한다 — `Math.round(null)`은 컴파일이 안 되고, 여기서 `0`으로 기본값을 채우면 "완료
  Job이 없다"와 "평균이 정확히 0ms다"를 구분 못 하게 된다.
- `getWorkersSummary()`가 이 Service에서 유일하게 자기 Repository가 아니라 **다른 도메인의
  Query Service**(`WorkerNodeQueryService`)를 부른다. `WorkerNodeQueryService.getWorkers()`가
  반환하는 `WorkerNodeResponse.status()`는 이미 Heartbeat 기준으로 계산된 값이라(`WorkerNode.resolveEffectiveStatus()`),
  여기서 그 판정 로직을 다시 만들 필요가 없다 — `ACTIVE`/`IDLE`인 것만 세면 된다.
- `getSearchSummary()`는 `LocalDateTime.now()`를 직접 안 쓰고 주입받은 `Clock`으로 현재 시각을
  구한다. 이러면 테스트에서 `Clock.fixed(...)`로 시간을 고정해 "24시간 전"을 결정론적으로
  검증할 수 있다(6.1절 테스트 참고).

### 4.6 `DashboardController`

```java
@Tag(name = "Admin - Dashboard", description = "관리자 전용 RAGOps Dashboard 집계 지표 API")
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardQueryService dashboardQueryService;

    @Operation(
        summary = "대시보드 집계 지표 조회",
        description = "문서·인덱싱 작업·Worker·검색 현황을 하나의 응답으로 집계해서 반환합니다. "
            + "모든 지표는 조회 시점 기준 Snapshot이며, 실시간 WebSocket push는 이 API의 범위가 아닙니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "집계 지표 조회 성공"),
        @ApiResponse(
            responseCode = "403",
            description = "인증되지 않았거나 ADMIN 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> getSummary() {
        return ResponseUtils.ok(dashboardQueryService.getSummary());
    }
}
```

`@RequestMapping("/admin/dashboard")` + `@GetMapping("/summary")`로 최종 경로가
`/admin/dashboard/summary`가 된다. `/admin/**`는 `SecurityConfig`에 이미 `hasRole("ADMIN")`
규칙이 있어서 이 Controller엔 별도 권한 어노테이션이 없다 — 경로만 `/admin` 아래 두면 자동으로
ADMIN 전용이 된다. 메서드 본문은 `dashboardQueryService.getSummary()` 결과를
`ResponseUtils.ok()`로 감싸는 게 전부다 — 입력 파라미터가 없어서 검증 로직도 없다.

`description`의 마지막 문장("실시간 WebSocket push는 이 API의 범위가 아닙니다")은 CodeRabbit
리뷰로 정정한 부분이다. 원래는 "WebSocket push로 제공됩니다"라고 써서, 아직 구현되지도 않은
기능(이슈#137에서 구현)이 이미 있는 것처럼 문서화하는 실수가 있었다.

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
