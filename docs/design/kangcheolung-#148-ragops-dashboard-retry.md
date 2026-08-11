# Issue #148 RAGOps Dashboard 관리자 재처리 API 상세 설계

closes #148

## 1. 배경과 목적

이슈#134(집계 조회)와 이슈#137(WebSocket push)로 대시보드가 "지금 FAILED 작업이 27건 있다"는
걸 실시간으로 보여줄 수 있게 됐다. 이번 작업은 그 화면에서 관리자가 실제로 개입할 수 있는
마지막 조각 — 재처리 버튼을 누르면 FAILED 작업을 다시 Queue에 넣고, 그 결과를 즉시 대시보드에
반영하는 API다.

FAILED 목록 조회(F-OPS-03)는 A 담당자가 이미 `GET /admin/indexing-jobs?status=FAILED`로
제공하고 있어 재사용하고, 이 이슈는 재처리 트리거(F-OPS-04 단건, F-OPS-05 전체)만 만든다.

### 1.1 성공 기준

- 단건 재처리 클릭 시 FAILED→PENDING 전환과 즉시 대시보드 push가 확인된다.
- 전체 재처리 시 정확한 재처리 건수를 반환한다 (개별 실패는 skip하고 성공 건수만 집계).
- `embedding_jobs`를 B가 직접 update하지 않는다 — 상태 전환은 전부 A의 Service를 경유한다.
- ADMIN이 아닌 사용자 요청은 403.

## 2. 범위

### 2.1 포함

- `POST /admin/embedding-jobs/{jobId}/retry` (단건)
- `POST /admin/embedding-jobs/retry-all` (전체)
- 재처리 성공 시 대시보드 WebSocket push
- 단위 테스트

### 2.2 제외

- FAILED 목록 조회 API — A의 기존 API 재사용, 신규 구현 없음
- `embedding_jobs` 상태 전환 로직 자체 — A 담당자 소유(`EmbeddingJobManualRetryService`)
- Worker 상태 전이 시점 이벤트 훅 — 별도 이슈
- 부하테스트 — 관리자 전용 저빈도 수동 트리거라 처리량 요구사항이 없어 범위에서 제외 (4.4절)

## 3. API 계약

```
POST /admin/embedding-jobs/{jobId}/retry     (단건)
POST /admin/embedding-jobs/retry-all         (전체)
Authorization: Bearer {JWT}
```

- ADMIN 역할만 호출 가능 (`SecurityConfig`의 `/admin/** -> hasRole("ADMIN")` 재사용)
- 단건 응답은 A의 `ManualRetriedIndexingJobResponse`를 그대로 반환한다 (4.1절 참고)
- 전체 응답은 `{ retriedCount, message }`

### 3.1 응답 예시

```json
// POST /admin/embedding-jobs/{jobId}/retry
{
  "jobId": 42,
  "status": "PENDING",
  "documentId": 3,
  "documentVersionId": 5,
  "documentVersionStatus": "CHUNKED",
  "retryCount": 3,
  "maxRetryCount": 3,
  "requeuedAt": "2026-08-11T15:00:00"
}

// POST /admin/embedding-jobs/retry-all
{
  "retriedCount": 27,
  "message": "27개 작업 재처리 요청이 완료되었습니다."
}
```

## 4. 구현 상세

읽는 순서는 DTO(4.1) → Service(4.2~4.4) → Controller(4.5)를 따른다.

### 4.1 응답 DTO — 새로 만든 건 하나뿐

단건 재처리는 새 DTO를 만들지 않고 A의 기존 `ManualRetriedIndexingJobResponse`를 그대로
재사용한다. 스펙 초안의 응답 예시(`{jobId, status, message}`)보다 A가 이미 documentId·
retryCount·requeuedAt 등 더 풍부한 정보를 주고 있어서, 필드를 깎아낸 새 DTO를 만드는 게
오히려 손해였다.

전체 재처리만 A쪽에 대응하는 응답 타입이 없어서 새로 만들었다:

```java
public record RetryAllJobsResponse(
    @Schema(description = "재처리에 성공한 Job 수", example = "27")
    int retriedCount,

    @Schema(description = "결과 메시지", example = "27개 작업 재처리 요청이 완료되었습니다.")
    String message
) {
}
```

### 4.2 `EmbeddingJobRetryService` — 단건 재처리

```java
public ManualRetriedIndexingJobResponse retryJob(Long jobId) {
    ManualRetriedIndexingJobResponse response = embeddingJobManualRetryService.retry(jobId);
    dashboardWebSocketController.sendDashboardUpdate(dashboardQueryService.getSummary());
    return response;
}
```

`embeddingJobManualRetryService`는 A 담당자가 만든 서비스를 그대로 주입받아 호출한다.
`retry(jobId)`가 예외 없이 반환하면(=성공) 바로 이슈1의 `DashboardQueryService.getSummary()`로
최신 집계를 다시 계산하고, 이슈2의 `DashboardWebSocketController.sendDashboardUpdate()`로
`/topic/dashboard` 구독자에게 push한다. 예외 처리 코드가 하나도 없는데, 이유는 4.4절 참고.

### 4.3 `EmbeddingJobRetryService` — 전체 재처리

```java
public RetryAllJobsResponse retryAllFailedJobs() {
    List<EmbeddingJob> failedJobs = embeddingJobRepository.findAllByStatus(EmbeddingJobStatus.FAILED);

    int retriedCount = 0;
    for (EmbeddingJob failedJob : failedJobs) {
        try {
            embeddingJobManualRetryService.retry(failedJob.getId());
            retriedCount++;
        } catch (Exception e) {
            log.warn("전체 재처리 중 Job 건너뜀: jobId={}, reason={}", failedJob.getId(), e.getMessage());
        }
    }

    if (retriedCount > 0) {
        dashboardWebSocketController.sendDashboardUpdate(dashboardQueryService.getSummary());
    }

    return new RetryAllJobsResponse(retriedCount, RETRY_ALL_MESSAGE_FORMAT.formatted(retriedCount));
}
```

- `embeddingJobRepository.findAllByStatus(FAILED)` — A의 REST 엔드포인트를 내부적으로 또
  호출하지 않고 Repository를 직접 읽는다. 이슈1에서 이 용도로 미리 추가해둔 메서드를 재사용한다.
  쓰기가 아니라 조회라서 "A 소유 테이블은 쓰기만 A 경유" 원칙과 충돌하지 않는다.
- `for` 루프 안에서 한 건씩 `try-catch`로 감싼다. 한 건이 실패해도 로그만 남기고 나머지는
  계속 처리한다 — "개별 실패는 skip하고 성공 건수만 집계" 요구사항이 이 catch 하나로 구현된다.
- push는 루프가 끝난 뒤 **한 번만**, `retriedCount > 0`일 때만 한다. 건마다 push하면 27건이면
  27번 push가 나가 "상태 변경 시점에만 push" 취지와 어긋나고, 성공이 하나도 없으면(전부 실패)
  실제로 바뀐 게 없으니 push 자체를 생략한다.

### 4.4 왜 클래스 레벨 `@Transactional`이 없는가

`service-pattern.md`는 Command Service에 클래스 레벨 `@Transactional`을 필수로 두라고 하는데,
이 클래스는 일부러 뺐다.

`retryAllFailedJobs()`가 이 메서드 자체에서 트랜잭션을 열면, 안에서 호출하는 A의
`embeddingJobManualRetryService.retry()`(자신도 `@Transactional`)가 그 바깥 트랜잭션에
참여(Propagation.REQUIRED, 기본값)하게 된다. 이 상태에서 예를 들어 3건 중 2번째 Job에서 예외가
나면, 스프링은 그 시점에 트랜잭션 전체를 **rollback-only로 표시**한다. 이후 `catch`로 예외를
잡아 무시해도 이 표시는 풀리지 않는다 — 결국 메서드가 끝나고 커밋하려는 순간 스프링이 rollback을
강제하면서, 이미 성공했던 1번째 Job의 재처리까지 전부 날아간다. "개별 실패는 건너뛰고 성공 건수만
집계한다"는 요구사항 자체가 깨지는 것이다.

그래서 `EmbeddingJobRetryService`는 트랜잭션을 열지 않는다. 그러면 `retry()`를 호출할 때마다
바깥에 활성 트랜잭션이 없으므로, A Service 자신의 `@Transactional`이 매번 **새 독립 트랜잭션**을
만들어 그 자리에서 바로 커밋한다. 한 건의 실패가 다른 건의 성공을 되돌리지 않는다.

이 근거는 Mockito 단위 테스트로는 증명할 수 없다 — Mock은 실제 스프링 트랜잭션 프록시를 통하지
않으므로 "독립 커밋"이 실제로 일어나는지 확인이 안 된다. 6.2절에서 이 부분만 실제 Postgres로
검증하는 통합 테스트를 별도로 추가했다.

### 4.5 `EmbeddingJobRetryController`

```java
@RequestMapping("/admin/embedding-jobs")
public class EmbeddingJobRetryController {

    @PostMapping("/{jobId}/retry")
    public ResponseEntity<ApiResponse<ManualRetriedIndexingJobResponse>> retryJob(
        @PathVariable @Positive Long jobId
    ) {
        return ResponseUtils.ok(embeddingJobRetryService.retryJob(jobId));
    }

    @PostMapping("/retry-all")
    public ResponseEntity<ApiResponse<RetryAllJobsResponse>> retryAllJobs() {
        return ResponseUtils.ok(embeddingJobRetryService.retryAllFailedJobs());
    }
}
```

`/admin/**`라 `SecurityConfig`의 기존 규칙으로 자동 ADMIN 전용이다. 예외 처리 코드가 컨트롤러에도
없다 — 5장 참고.

## 5. 오류 계약 — 별도 코드 없이 해결됨

`EmbeddingJobManualRetryService.retry()`(A 소유)가 이미 상황별로 다른 `ErrorCode`를 던진다:

```java
// jobId 없음
.orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));  // 404

// 상태가 FAILED가 아님
if (embeddingJob.getStatus() != EmbeddingJobStatus.FAILED) {
    throw new DocGridException(ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED);  // 409
}
```

그리고 프로젝트 전역 `GlobalExceptionHandler`가 `DocGridException`을 이미 공통 처리한다:

```java
@ExceptionHandler(DocGridException.class)
public ResponseEntity<ErrorResponse> handleDocGridException(DocGridException e, HttpServletRequest request) {
    return ResponseEntity.status(e.getErrorCode().getHttpStatus())
        .body(ErrorResponse.of(e.getErrorCode(), e.getMessage(), request));
}
```

| 상황 | 처리 |
|---|---|
| jobId 존재하지 않음 | A의 `EMBEDDING_JOB_NOT_FOUND` → 404 (B는 그대로 propagate) |
| FAILED 상태가 아님 | A의 `EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED` → 409 (B는 그대로 propagate) |
| ADMIN이 아닌 사용자 | `SecurityConfig`의 기존 `/admin/**` 정책 → 403 |
| 전체 재처리 시 FAILED 0건 | 루프가 빈 목록을 돌아 `retriedCount: 0`으로 200 정상 응답 |
| 전체 재처리 중 개별 Job 실패 | `catch (Exception e)`로 흡수, `log.warn`만 남기고 계속 진행 |

B가 사전에 `EmbeddingJobRepository.findById()`로 존재/상태를 확인하고 직접 404/409를 판단할
필요가 전혀 없다 — A가 이미 구분해서 던지고, 기존 인프라가 이미 처리하기 때문이다.

## 6. 테스트 설계

### 6.1 단위 테스트 — `EmbeddingJobRetryServiceTest`

전부 Mockito로 A Service·Repository·대시보드 컴포넌트를 mock 처리한다.

- 단건 재처리 → A 호출 + push 확인
- 3건 중 1건 실패 → `retriedCount == 2`, push는 됨
- FAILED 0건 → `retriedCount == 0`, push 안 됨
- 전부 실패 → `retriedCount == 0`, push 안 됨

### 6.2 PostgreSQL 통합 테스트 — 트랜잭션 독립성 검증 (`EmbeddingJobRetryTransactionIsolationIntegrationTest`)

4.4절의 핵심 주장("한 Job의 실패가 다른 Job의 성공을 롤백하지 않는다")은 Mock으로 증명할 수
없다는 판단으로, 이슈 To-do에는 없었지만 검증 공백을 메우기 위해 추가했다.

`@SpringBootTest`로 A의 `EmbeddingJobManualRetryService`를 Mock 없이 실제 빈으로 띄우고,
실제 Postgres에 FAILED 상태 Job 3건을 직접 SQL로 만든다 — 2건은 정상 재처리 대상, 1건은 대상
문서를 soft-delete(`deleted_at` 설정)해서 A의 `validateRetryTarget()`이 실제 검증 로직으로
`EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID`를 던지도록 유도한다. `retryAllFailedJobs()` 실행
후:

- `retriedCount == 2`
- 성공한 2건은 DB에서 직접 조회했을 때 실제로 `status = 'PENDING'`으로 커밋돼 있음
- 실패한 1건은 `status = 'FAILED'`로 그대로 남아 있음(변경 없음)

을 확인해서, "일부 실패해도 나머지는 진짜 커밋된다"는 설계 근거를 Mock이 아닌 실제 트랜잭션
경계로 검증했다.

## 7. 커밋 분할

1. `docs: #148 RAGOps Dashboard 관리자 재처리 API 설계 문서 추가`
2. `feat: #148 전체 재처리 응답 DTO 추가`
3. `feat: #148 관리자 재처리 Command Service 구현`
4. `feat: #148 관리자 재처리 Controller 구현`
5. `test: #148 관리자 재처리 단위 테스트 추가`
6. `test: #148 전체 재처리 트랜잭션 독립성 PostgreSQL 통합 테스트 추가`

## 8. 완료 조건

- 단건 재처리 클릭 시 FAILED→PENDING 전환과 즉시 대시보드 push가 확인된다
- 전체 재처리 시 정확한 재처리 건수를 반환한다 (개별 실패는 skip하고 성공 건수만 집계)
- 전체 재처리 중 일부 Job이 실패해도 나머지 성공한 Job은 실제 Postgres에 독립적으로 커밋된다
  (Mock이 아닌 통합 테스트로 검증)
- ADMIN이 아닌 사용자 요청은 403
- `embedding_jobs`를 B가 직접 update하지 않는다
- 전체 빌드(`./gradlew build`)가 회귀 없이 통과한다 (751개 테스트, failures 0, errors 0)
