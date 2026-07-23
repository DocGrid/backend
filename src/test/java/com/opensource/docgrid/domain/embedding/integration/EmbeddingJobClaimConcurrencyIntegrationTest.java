package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;

import lombok.extern.slf4j.Slf4j;

/**
 * 실제 OpenSQL에서 다중 Worker의 Embedding Job Claim 정합성을 대규모 경쟁 조건으로 검증하는 통합 테스트.
 *
 * <p>단일 Job에 100개 Worker가 경쟁하는 Burst와 20개 Worker가 1,000개 Job을 소진하는 Queue 시나리오를
 * 실행한다. 응답 개수뿐 아니라 최종 DB의 Worker, Claim Token, Lease, LOCKED 이벤트까지 비교해 한 Job의
 * 소유권이 정확히 한 번만 Commit됐는지 확인한다.
 */
@Slf4j
@Tag("integration")
@Tag("claim-concurrency")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Embedding Job Claim 다중 Worker 정합성 통합 테스트")
class EmbeddingJobClaimConcurrencyIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_embedding_job_claim_concurrency_test";
    private static final int SINGLE_JOB_WORKER_COUNT = 100;
    private static final int MULTI_JOB_WORKER_COUNT = 20;
    private static final int MULTI_JOB_COUNT = 1_000;
    private static final int DB_CONNECTION_POOL_SIZE = 20;
    private static final long READY_TIMEOUT_SECONDS = 30;
    private static final long TASK_TIMEOUT_SECONDS = 120;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final String KEEP_SCHEMA_ENVIRONMENT_VARIABLE = "KEEP_CLAIM_CONCURRENCY_SCHEMA";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddingJobClaimService embeddingJobClaimService;

    private final AtomicInteger threadSequence = new AtomicInteger();

    @DynamicPropertySource
    static void configureConcurrencyEnvironment(DynamicPropertyRegistry registry) {
        // 100개 Java Task를 그대로 DB Connection 100개로 연결하지 않고 운영과 유사한 Pool Backpressure를 둔다.
        registry.add("spring.datasource.hikari.pool-name", () -> "claim-concurrency-test-pool");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> DB_CONNECTION_POOL_SIZE);
        registry.add("spring.datasource.hikari.minimum-idle", () -> DB_CONNECTION_POOL_SIZE);
        // HikariDataSource의 connectionTimeout은 Duration 문자열이 아니라 밀리초 long 값으로 바인딩된다.
        registry.add("spring.datasource.hikari.connection-timeout", () -> 60_000L);

        // Heartbeat Scheduler 없이 반복 Claim해도 정상 Worker가 테스트 중 DEAD로 바뀌지 않게 한다.
        registry.add("indexing.worker.dead-threshold", () -> "10m");
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
    }

    @BeforeEach
    void resetClaimState() {
        // FK 역순으로 테스트가 생성한 Claim 데이터만 제거하고 Flyway가 등록한 기본 모델은 유지한다.
        jdbcTemplate.update("DELETE FROM indexing_events");
        jdbcTemplate.update("DELETE FROM embedding_job_attempts");
        jdbcTemplate.update("DELETE FROM embedding_jobs");
        jdbcTemplate.update("DELETE FROM worker_nodes");
        jdbcTemplate.update("DELETE FROM document_versions WHERE title_snapshot LIKE 'Claim Concurrency%'");
        jdbcTemplate.update("DELETE FROM documents WHERE title LIKE 'Claim Concurrency%'");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'claim-concurrency-%'");
    }

    @AfterAll
    void dropIsolatedSchema() {
        // 수동 SQL 검증을 명시적으로 요청한 실행에서는 결과 스키마를 남기고 가이드의 정리 명령으로 제거한다.
        if (Boolean.parseBoolean(System.getenv(KEEP_SCHEMA_ENVIRONMENT_VARIABLE))) {
            log.warn(
                "수동 검증을 위해 테스트 스키마를 유지합니다: schema={}, environmentVariable={}",
                TEST_SCHEMA,
                KEEP_SCHEMA_ENVIRONMENT_VARIABLE
            );
            return;
        }
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @Order(1)
    @DisplayName("ACTIVE Worker 100개가 Job 하나를 동시에 Claim해도 한 Worker만 성공한다")
    void claim_singleJobWithOneHundredWorkers_assignsExactlyOneOwner() throws Exception {
        Long documentVersionId = insertDocumentVersion("Single Job");
        Long jobId = insertPendingJobs(documentVersionId, 1).get(0);
        List<Long> workerIds = insertActiveWorkers(SINGLE_JOB_WORKER_COUNT, "single");

        long startedAt = System.nanoTime();

        // 1. 서로 다른 Worker 100개가 모두 준비된 뒤 같은 시점에 Claim Service 호출을 시작한다.
        List<ClaimAttemptResult> attempts = runConcurrently(workerIds, this::attemptSingleClaim);

        // 2. Thread 내부 오류가 없고 성공 한 건과 정상적인 빈 Queue 결과 99건만 존재하는지 확인한다.
        assertThat(failureDescriptions(attempts)).isEmpty();
        List<ClaimedEmbeddingJobResponse> claimedJobs = attempts.stream()
            .flatMap(attempt -> attempt.claimedJob().stream())
            .toList();
        assertThat(claimedJobs).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> attempt.claimedJob().isEmpty()).hasSize(99);

        // 3. 유일한 성공 응답이 등록된 Worker와 유효한 UUID Token 및 Lease를 가지는지 검증한다.
        ClaimedEmbeddingJobResponse claimedJob = claimedJobs.get(0);
        assertThat(claimedJob.jobId()).isEqualTo(jobId);
        assertThat(claimedJob.workerId()).isIn(workerIds);
        assertThatUuid(claimedJob.claimToken());
        assertThat(claimedJob.lockExpiresAt()).isAfter(claimedJob.lockedAt());

        // 4. 응답만 맞고 DB가 다르게 Commit되는 부분 성공을 막기 위해 소유권 Row와 이벤트를 대조한다.
        JobOwnershipSnapshot snapshot = findOwnershipSnapshots().get(jobId);
        assertOwnershipMatches(claimedJob, snapshot);
        assertThat(countLockedEvents()).isEqualTo(1);
        assertThat(countJobsWithInvalidLockedEventCount()).isZero();

        log.info(
            "단일 Job Claim 경쟁 완료: workers={}, success=1, empty=99, elapsedMs={}",
            SINGLE_JOB_WORKER_COUNT,
            elapsedMillis(startedAt)
        );
    }

    @Test
    @Order(2)
    @DisplayName("ACTIVE Worker 20개가 PENDING Job 1,000개를 중복과 누락 없이 Claim한다")
    void claim_oneThousandJobsWithTwentyWorkers_claimsEveryJobExactlyOnce() throws Exception {
        Long documentVersionId = insertDocumentVersion("One Thousand Jobs");
        List<Long> seededJobIds = insertPendingJobs(documentVersionId, MULTI_JOB_COUNT);
        List<Long> workerIds = insertActiveWorkers(MULTI_JOB_WORKER_COUNT, "queue");

        long startedAt = System.nanoTime();

        // 1. Worker 20개가 동시에 시작해 빈 결과를 받을 때까지 각자 독립 Transaction으로 반복 Claim한다.
        List<WorkerClaimResult> workerResults = runConcurrently(workerIds, this::claimUntilQueueIsEmpty);

        // 2. Worker 오류 없이 정확히 1,000개의 고유 Job 응답을 받았는지 집계한다.
        assertThat(workerFailureDescriptions(workerResults)).isEmpty();
        List<ClaimedEmbeddingJobResponse> claimedJobs = workerResults.stream()
            .flatMap(result -> result.claimedJobs().stream())
            .toList();
        assertThat(claimedJobs).hasSize(MULTI_JOB_COUNT);
        assertThat(claimedJobs).extracting(ClaimedEmbeddingJobResponse::jobId).doesNotHaveDuplicates();
        assertThat(new HashSet<>(claimedJobs.stream().map(ClaimedEmbeddingJobResponse::jobId).toList()))
            .isEqualTo(new HashSet<>(seededJobIds));

        // 3. 모든 응답의 Worker와 Token이 유효하고 최종 DB 소유권과 정확히 일치하는지 Job별로 확인한다.
        assertThat(claimedJobs).extracting(ClaimedEmbeddingJobResponse::workerId)
            .allMatch(workerIds::contains);
        assertThat(claimedJobs).extracting(ClaimedEmbeddingJobResponse::claimToken)
            .doesNotHaveDuplicates()
            .allSatisfy(this::assertThatUuid);

        Map<Long, JobOwnershipSnapshot> snapshots = findOwnershipSnapshots();
        assertThat(snapshots).hasSize(MULTI_JOB_COUNT);
        claimedJobs.forEach(claimedJob -> assertOwnershipMatches(claimedJob, snapshots.get(claimedJob.jobId())));

        // 4. Queue 완전 소진과 Job별 단일 LOCKED 이벤트를 검증해 누락·중복 Commit이 없음을 확정한다.
        assertThat(countJobsByStatus("PENDING")).isZero();
        assertThat(countJobsByStatus("PROCESSING")).isEqualTo(MULTI_JOB_COUNT);
        assertThat(countJobsWithIncompleteOwnership()).isZero();
        assertThat(countLockedEvents()).isEqualTo(MULTI_JOB_COUNT);
        assertThat(countJobsWithInvalidLockedEventCount()).isZero();

        logWorkerDistribution(workerResults);
        log.info(
            "다중 Job Claim 경쟁 완료: workers={}, jobs={}, uniqueClaims={}, elapsedMs={}",
            MULTI_JOB_WORKER_COUNT,
            MULTI_JOB_COUNT,
            claimedJobs.size(),
            elapsedMillis(startedAt)
        );
    }

    private ClaimAttemptResult attemptSingleClaim(Long workerId) {
        try {
            return new ClaimAttemptResult(workerId, embeddingJobClaimService.claim(workerId), null);
        } catch (RuntimeException exception) {
            return new ClaimAttemptResult(workerId, Optional.empty(), exception);
        }
    }

    private WorkerClaimResult claimUntilQueueIsEmpty(Long workerId) {
        List<ClaimedEmbeddingJobResponse> claimedJobs = new ArrayList<>();
        try {
            while (claimedJobs.size() <= MULTI_JOB_COUNT) {
                Optional<ClaimedEmbeddingJobResponse> claimedJob = embeddingJobClaimService.claim(workerId);
                if (claimedJob.isEmpty()) {
                    return new WorkerClaimResult(workerId, List.copyOf(claimedJobs), null);
                }
                claimedJobs.add(claimedJob.get());
            }
            return new WorkerClaimResult(
                workerId,
                List.copyOf(claimedJobs),
                new IllegalStateException("한 Worker의 Claim 수가 전체 준비 Job 수를 초과했습니다.")
            );
        } catch (RuntimeException exception) {
            return new WorkerClaimResult(workerId, List.copyOf(claimedJobs), exception);
        }
    }

    private <T> List<T> runConcurrently(List<Long> workerIds, Function<Long, T> workerAction) throws Exception {
        CountDownLatch readyLatch = new CountDownLatch(workerIds.size());
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(workerIds.size(), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("job-claim-concurrency-" + threadSequence.incrementAndGet());
            return thread;
        });

        List<Future<T>> futures = new ArrayList<>();
        try {
            // 1. Executor Thread 수를 Worker 수와 같게 만들어 모든 Task가 Start Gate에 도달할 수 있게 한다.
            for (Long workerId : workerIds) {
                futures.add(executorService.submit(() -> {
                    readyLatch.countDown();
                    awaitLatch(startLatch, TASK_TIMEOUT_SECONDS, "동시 Claim 시작");
                    return workerAction.apply(workerId);
                }));
            }

            // 2. 모든 Worker가 준비되기 전에 일부 Worker가 먼저 Claim하는 순차 실행을 차단한다.
            assertThat(readyLatch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("모든 Worker Thread가 제한 시간 안에 준비되어야 한다")
                .isTrue();

            // 3. 하나의 Gate를 열어 준비된 모든 Worker가 Claim Service 호출을 시작하게 한다.
            startLatch.countDown();

            // 4. 전체 제한 시간을 공유해 한 Future마다 Timeout이 누적되는 장시간 대기를 방지한다.
            return awaitFuturesWithinSharedDeadline(futures);
        } finally {
            startLatch.countDown();
            shutdownExecutor(executorService);
        }
    }

    private <T> List<T> awaitFuturesWithinSharedDeadline(List<Future<T>> futures) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TASK_TIMEOUT_SECONDS);
        List<T> results = new ArrayList<>(futures.size());

        for (Future<T> future : futures) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("다중 Worker Claim이 전체 제한 시간을 초과했습니다.");
            }
            try {
                results.add(future.get(remainingNanos, TimeUnit.NANOSECONDS));
            } catch (ExecutionException exception) {
                throw new IllegalStateException("Worker Thread에서 예상하지 못한 오류가 발생했습니다.", exception.getCause());
            }
        }
        return results;
    }

    private void shutdownExecutor(ExecutorService executorService) throws InterruptedException {
        executorService.shutdown();
        if (executorService.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return;
        }

        executorService.shutdownNow();
        assertThat(executorService.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .as("동시성 테스트 Executor가 강제 종료 후 제한 시간 안에 종료되어야 한다")
            .isTrue();
    }

    private void awaitLatch(CountDownLatch latch, long timeoutSeconds, String description) {
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException(description + " 대기가 제한 시간을 초과했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(description + " 대기 중 Thread가 중단되었습니다.", exception);
        }
    }

    private Long insertDocumentVersion(String scenarioName) {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Claim Concurrency User', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "claim-concurrency-" + suffix + "@example.com");
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id,
                title,
                document_type,
                source_type,
                status,
                visibility,
                created_at,
                updated_at
            )
            VALUES (?, ?, 'TXT', 'UPLOAD', 'UPLOADED', 'PRIVATE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId, "Claim Concurrency " + scenarioName + " " + suffix);
        return jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id,
                version_no,
                title_snapshot,
                status,
                created_by,
                created_at,
                updated_at
            )
            VALUES (?, 1, ?, 'UPLOADED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, "Claim Concurrency " + scenarioName, userId);
    }

    private List<Long> insertPendingJobs(Long documentVersionId, int jobCount) {
        int insertedCount = jdbcTemplate.update("""
            INSERT INTO embedding_jobs (
                document_version_id,
                embedding_model_id,
                status,
                priority,
                retry_count,
                max_retry_count,
                created_at,
                updated_at
            )
            SELECT ?,
                   model.id,
                   'PENDING',
                   MOD(series_no, 5),
                   0,
                   3,
                   TIMESTAMP '2026-07-23 00:00:00' + series_no * INTERVAL '1 microsecond',
                   TIMESTAMP '2026-07-23 00:00:00' + series_no * INTERVAL '1 microsecond'
            FROM embedding_models model
            CROSS JOIN generate_series(1, ?) AS series(series_no)
            WHERE model.is_active = TRUE
              AND model.is_searchable = TRUE
            """, documentVersionId, jobCount);

        assertThat(insertedCount).isEqualTo(jobCount);
        return jdbcTemplate.queryForList(
            "SELECT id FROM embedding_jobs ORDER BY id",
            Long.class
        );
    }

    private List<Long> insertActiveWorkers(int workerCount, String scenarioPrefix) {
        // instance_id VARCHAR(64) 제약 안에서 시나리오와 실행을 구분할 수 있는 짧은 접두사를 만든다.
        String instancePrefix = "cc-" + scenarioPrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        int insertedCount = jdbcTemplate.update("""
            INSERT INTO worker_nodes (
                worker_name,
                instance_id,
                host_name,
                ip_address,
                status,
                last_heartbeat_at,
                started_at,
                created_at,
                updated_at
            )
            SELECT 'indexing-worker',
                   CONCAT(?, '-', series_no),
                   'localhost',
                   '127.0.0.1',
                   'ACTIVE',
                   CURRENT_TIMESTAMP,
                   CURRENT_TIMESTAMP,
                   CURRENT_TIMESTAMP,
                   CURRENT_TIMESTAMP
            FROM generate_series(1, ?) AS series(series_no)
            """, instancePrefix, workerCount);

        assertThat(insertedCount).isEqualTo(workerCount);
        return jdbcTemplate.queryForList(
            "SELECT id FROM worker_nodes WHERE instance_id LIKE ? ORDER BY id",
            Long.class,
            instancePrefix + "-%"
        );
    }

    private Map<Long, JobOwnershipSnapshot> findOwnershipSnapshots() {
        return jdbcTemplate.query("""
            SELECT id,
                   status,
                   locked_by_worker_id,
                   claim_token,
                   locked_at,
                   lock_expires_at
            FROM embedding_jobs
            ORDER BY id
            """, (resultSet, rowNumber) -> new JobOwnershipSnapshot(
            resultSet.getLong("id"),
            resultSet.getString("status"),
            resultSet.getObject("locked_by_worker_id", Long.class),
            resultSet.getString("claim_token"),
            resultSet.getObject("locked_at", LocalDateTime.class),
            resultSet.getObject("lock_expires_at", LocalDateTime.class)
        )).stream().collect(Collectors.toMap(JobOwnershipSnapshot::jobId, Function.identity()));
    }

    private void assertOwnershipMatches(
        ClaimedEmbeddingJobResponse claimedJob,
        JobOwnershipSnapshot snapshot
    ) {
        assertThat(snapshot).as("Job %s의 DB 소유권이 존재해야 한다", claimedJob.jobId()).isNotNull();
        assertThat(snapshot.status()).isEqualTo("PROCESSING");
        assertThat(snapshot.workerId()).isEqualTo(claimedJob.workerId());
        assertThat(snapshot.claimToken()).isEqualTo(claimedJob.claimToken());
        assertThat(snapshot.lockedAt()).isCloseTo(claimedJob.lockedAt(), within(1, ChronoUnit.MILLIS));
        assertThat(snapshot.lockExpiresAt())
            .isCloseTo(claimedJob.lockExpiresAt(), within(1, ChronoUnit.MILLIS))
            .isAfter(snapshot.lockedAt());
    }

    private int countJobsByStatus(String status) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM embedding_jobs WHERE status = ?",
            Integer.class,
            status
        );
    }

    private int countJobsWithIncompleteOwnership() {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM embedding_jobs
            WHERE status = 'PROCESSING'
              AND (
                    locked_by_worker_id IS NULL
                 OR claim_token IS NULL
                 OR locked_at IS NULL
                 OR lock_expires_at IS NULL
              )
            """, Integer.class);
    }

    private int countLockedEvents() {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM indexing_events
            WHERE event_type = 'LOCKED'
            """, Integer.class);
    }

    private int countJobsWithInvalidLockedEventCount() {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM (
                SELECT job.id
                FROM embedding_jobs job
                LEFT JOIN indexing_events event
                       ON event.embedding_job_id = job.id
                      AND event.event_type = 'LOCKED'
                GROUP BY job.id
                HAVING COUNT(event.id) <> 1
            ) invalid_job
            """, Integer.class);
    }

    private List<String> failureDescriptions(List<ClaimAttemptResult> attempts) {
        return attempts.stream()
            .filter(attempt -> attempt.failure() != null)
            .map(attempt -> formatFailure(attempt.workerId(), attempt.failure()))
            .toList();
    }

    private List<String> workerFailureDescriptions(List<WorkerClaimResult> workerResults) {
        return workerResults.stream()
            .filter(result -> result.failure() != null)
            .map(result -> formatFailure(result.workerId(), result.failure()))
            .toList();
    }

    private String formatFailure(Long workerId, RuntimeException failure) {
        return "workerId=" + workerId
            + ", type=" + failure.getClass().getSimpleName()
            + ", message=" + failure.getMessage();
    }

    private void assertThatUuid(String value) {
        assertThat(value).isNotBlank();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }

    private void logWorkerDistribution(List<WorkerClaimResult> workerResults) {
        workerResults.stream()
            .sorted((left, right) -> Long.compare(left.workerId(), right.workerId()))
            .forEach(result -> log.info(
                "Worker Claim 분포: workerId={}, claimedJobs={}",
                result.workerId(),
                result.claimedJobs().size()
            ));
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /**
     * 단일 Claim 요청의 Worker, 응답, 실패를 함께 보존해 100개 Thread 결과를 누락 없이 진단하는 값 객체.
     */
    private record ClaimAttemptResult(
        Long workerId,
        Optional<ClaimedEmbeddingJobResponse> claimedJob,
        RuntimeException failure
    ) {
    }

    /**
     * 한 Worker가 Queue 종료까지 Claim한 응답과 실패를 묶어 Worker별 처리 분포와 오류를 진단하는 값 객체.
     */
    private record WorkerClaimResult(
        Long workerId,
        List<ClaimedEmbeddingJobResponse> claimedJobs,
        RuntimeException failure
    ) {
    }

    /**
     * Claim 응답과 최종 DB Row의 소유권을 Job ID 기준으로 비교하기 위한 조회 전용 값 객체.
     */
    private record JobOwnershipSnapshot(
        Long jobId,
        String status,
        Long workerId,
        String claimToken,
        LocalDateTime lockedAt,
        LocalDateTime lockExpiresAt
    ) {
    }
}
