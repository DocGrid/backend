package com.opensource.docgrid.domain.embedding.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL 17과 pgvector 0.8.1에서 Embedding Job Claim 처리량과 자원 경합의 기준선을 수집하는 Benchmark.
 *
 * <p>Spring이 관리하는 실제 Claim Service를 Worker 수별로 동시에 호출해 Transaction Commit을 포함한
 * 호출 지연과 Queue 소진 시간을 측정한다. Production 코드를 변경하지 않고 Hikari Pool과 PostgreSQL
 * 대기 상태를 별도 Monitoring Connection으로 관찰하며, 모든 성능 Profile에서 Claim 소유권 정합성을
 * 다시 검증한다.
 */
@Slf4j
@Tag("benchmark")
@Tag("claim-performance")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Embedding Job Claim 처리량·경합 Benchmark")
class EmbeddingJobClaimPerformanceBenchmark {

    private static final String TEST_SCHEMA = "docgrid_embedding_job_claim_performance_test";
    private static final String WORKER_APPLICATION_NAME = "docgrid-claim-performance-worker";
    private static final String MONITOR_APPLICATION_NAME = "docgrid-claim-performance-monitor";
    private static final String KEEP_SCHEMA_ENVIRONMENT_VARIABLE = "KEEP_CLAIM_PERFORMANCE_SCHEMA";
    private static final String RESULT_PREFIX = "CLAIM_PERFORMANCE_RESULT";
    private static final String MEDIAN_PREFIX = "CLAIM_PERFORMANCE_MEDIAN";
    private static final String ENVIRONMENT_PREFIX = "CLAIM_PERFORMANCE_ENV";
    private static final String EXPECTED_POSTGRES_VERSION_PREFIX = "17.";
    private static final String EXPECTED_PGVECTOR_VERSION = "0.8.1";

    private static final int WARM_UP_WORKER_COUNT = 10;
    private static final int WARM_UP_JOB_COUNT = positiveIntegerProperty(
        "claim.performance.warm-up-jobs",
        500
    );
    private static final int MEASURED_JOB_COUNT = positiveIntegerProperty(
        "claim.performance.job-count",
        5_000
    );
    private static final int MEASURED_REPETITIONS = positiveIntegerProperty(
        "claim.performance.repetitions",
        5
    );
    private static final List<Integer> WORKER_COUNTS = positiveIntegerListProperty(
        "claim.performance.workers",
        List.of(1, 5, 10, 20, 40)
    );
    private static final int DB_CONNECTION_POOL_SIZE = 20;
    private static final long SAMPLING_INTERVAL_MILLIS = positiveLongProperty(
        "claim.performance.sampling-interval-ms",
        25L
    );
    private static final long READY_TIMEOUT_SECONDS = 30;
    private static final long PROFILE_TIMEOUT_SECONDS = positiveLongProperty(
        "claim.performance.profile-timeout-seconds",
        300L
    );
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final long SAMPLER_START_TIMEOUT_SECONDS = 10;
    private static final long SAMPLER_STOP_TIMEOUT_SECONDS = 10;
    private static final long DATABASE_STATS_SETTLE_MILLIS = 1_100;

    private static final String LOCK_WAITER_COUNT_SQL = """
        SELECT COUNT(DISTINCT activity.pid)
        FROM pg_stat_activity activity
        JOIN pg_locks waiting_lock
          ON waiting_lock.pid = activity.pid
         AND waiting_lock.granted = FALSE
        WHERE activity.datname = current_database()
          AND activity.application_name = ?
        """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddingJobClaimService embeddingJobClaimService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger workerThreadSequence = new AtomicInteger();

    @DynamicPropertySource
    static void configurePerformanceEnvironment(DynamicPropertyRegistry registry) {
        // 1. Worker 수가 Pool 크기를 초과하는 Profile에서 실제 Connection Backpressure가 발생하게 한다.
        registry.add("spring.datasource.hikari.pool-name", () -> "claim-performance-test-pool");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> DB_CONNECTION_POOL_SIZE);
        registry.add("spring.datasource.hikari.minimum-idle", () -> DB_CONNECTION_POOL_SIZE);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 60_000L);

        // 2. pg_stat_activity에서 Benchmark Worker Connection만 정확히 구분할 수 있게 이름을 고정한다.
        registry.add(
            "spring.datasource.hikari.data-source-properties.ApplicationName",
            () -> WORKER_APPLICATION_NAME
        );

        // 3. Scheduler 없이 장시간 반복 Claim해도 준비된 Worker가 DEAD로 해석되지 않게 한다.
        registry.add("indexing.worker.dead-threshold", () -> "2h");
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
    }

    @AfterAll
    void dropIsolatedSchema() {
        // 명시적으로 보존한 경우에만 마지막 Profile 데이터를 수동 SQL 확인용으로 남긴다.
        if (Boolean.parseBoolean(System.getenv(KEEP_SCHEMA_ENVIRONMENT_VARIABLE))) {
            log.warn(
                "수동 검증을 위해 Benchmark 스키마를 유지합니다: schema={}, environmentVariable={}",
                TEST_SCHEMA,
                KEEP_SCHEMA_ENVIRONMENT_VARIABLE
            );
            return;
        }
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("Worker 동시성별 처리량, 지연, Hikari 대기, PostgreSQL Lock 대기를 측정한다")
    void measureClaimThroughputAndContentionByWorkerCount() throws Exception {
        // 1. 잘못된 DB나 Application Name에서 얻은 수치를 기준선으로 남기지 않도록 실행 환경을 검증한다.
        validateBenchmarkEnvironment();
        logJson(ENVIRONMENT_PREFIX, collectEnvironmentFingerprint());

        // 2. Migration, JPA Metadata, Connection Pool과 주요 SQL 경로를 예열하되 측정 표본에는 포함하지 않는다.
        runWarmUp();
        awaitDatabaseStatsFlush();

        // 3. 같은 데이터 분포로 Worker 수와 반복만 바꿔 Profile별 원시 결과를 수집한다.
        List<ProfileRunSummary> runSummaries = new ArrayList<>();
        for (int workerCount : WORKER_COUNTS) {
            for (int repetition = 1; repetition <= MEASURED_REPETITIONS; repetition++) {
                ProfileRunSummary runSummary = runMeasuredProfile(workerCount, repetition);
                runSummaries.add(runSummary);
                logJson(RESULT_PREFIX, runSummary);
            }
        }

        // 4. 장비 내 변동을 줄여 비교할 수 있도록 Worker별 반복 결과의 중앙값을 별도로 출력한다.
        for (int workerCount : WORKER_COUNTS) {
            List<ProfileRunSummary> workerSummaries = runSummaries.stream()
                .filter(summary -> summary.workerCount() == workerCount)
                .toList();
            logJson(MEDIAN_PREFIX, ProfileMedianSummary.from(workerSummaries));
        }
    }

    private void validateBenchmarkEnvironment() throws SQLException {
        assertThat(jdbcTemplate.queryForObject("SELECT current_schema()", String.class))
            .as("Benchmark는 전용 Schema에서만 실행되어야 한다")
            .isEqualTo(TEST_SCHEMA);
        assertThat(jdbcTemplate.queryForObject("SHOW application_name", String.class))
            .as("PostgreSQL 대기 Session을 구분할 Worker Application Name이 적용되어야 한다")
            .isEqualTo(WORKER_APPLICATION_NAME);
        assertThat(jdbcTemplate.queryForObject("SHOW server_version", String.class))
            .as("설계 기준 PostgreSQL 17 환경이어야 한다")
            .startsWith(EXPECTED_POSTGRES_VERSION_PREFIX);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
            String.class
        )).as("설계 기준 pgvector 0.8.1 Extension이 준비되어야 한다")
            .isEqualTo(EXPECTED_PGVECTOR_VERSION);

        HikariDataSource hikariDataSource = hikariDataSource();
        assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(DB_CONNECTION_POOL_SIZE);
        assertThat(hikariDataSource.getMinimumIdle()).isEqualTo(DB_CONNECTION_POOL_SIZE);
        assertThat(hikariDataSource.getHikariPoolMXBean())
            .as("Hikari 대기 상태를 수집할 Pool MXBean이 준비되어야 한다")
            .isNotNull();
    }

    private void runWarmUp() throws Exception {
        resetProfileData();
        ProfileSeed seed = seedProfile("warm-up", WARM_UP_JOB_COUNT, WARM_UP_WORKER_COUNT);

        ProfileExecution execution = executeWorkers(seed.workerIds(), WARM_UP_JOB_COUNT);
        assertProfileConsistency(seed, execution);

        Map<String, Object> warmUpResult = new LinkedHashMap<>();
        warmUpResult.put("workerCount", WARM_UP_WORKER_COUNT);
        warmUpResult.put("jobCount", WARM_UP_JOB_COUNT);
        warmUpResult.put("measured", false);
        logJson("CLAIM_PERFORMANCE_WARMUP", warmUpResult);
    }

    private ProfileRunSummary runMeasuredProfile(int workerCount, int repetition) throws Exception {
        resetProfileData();
        ProfileSeed seed = seedProfile(
            "workers-" + workerCount + "-run-" + repetition,
            MEASURED_JOB_COUNT,
            workerCount
        );

        // Seed와 이전 Profile의 Backend 로컬 통계를 먼저 보고해 측정 구간 밖으로 분리한다.
        awaitDatabaseStatsFlush();
        forceHikariBackendStatsFlush();
        awaitDatabaseStatsFlush();

        ProfileExecution execution;
        WaitSummary waitSummary;
        DatabaseStats transactionDelta;

        try (Connection statsConnection = openMonitoringConnection()) {
            statsConnection.setReadOnly(true);
            statsConnection.setAutoCommit(false);
            DatabaseStats beforeStats = readDatabaseStats(statsConnection);

            // Monitoring Connection은 Worker Hikari Pool 밖에서 열어 관측 때문에 가용 Connection이 줄지 않게 한다.
            try (WaitingSampler sampler = new WaitingSampler()) {
                sampler.start();
                execution = executeWorkers(seed.workerIds(), MEASURED_JOB_COUNT);
                waitSummary = sampler.stopAndSummarize();
            }

            // 20개 Pool Backend가 로컬 누적 통계를 보고한 뒤 같은 read-only 창에서 최신 Snapshot을 다시 읽는다.
            awaitDatabaseStatsFlush();
            forceHikariBackendStatsFlush();
            awaitDatabaseStatsFlush();
            clearDatabaseStatsSnapshot(statsConnection);
            DatabaseStats afterStats = readDatabaseStats(statsConnection);
            transactionDelta = afterStats.minus(beforeStats);
            statsConnection.rollback();
        }

        ConsistencySummary consistencySummary = assertProfileConsistency(seed, execution);
        assertThat(transactionDelta.deadlocks())
            .as("Claim Profile 실행 중 PostgreSQL Deadlock이 증가하지 않아야 한다")
            .isZero();

        return summarizeProfile(
            workerCount,
            repetition,
            execution,
            consistencySummary,
            waitSummary,
            transactionDelta
        );
    }

    private ProfileSeed seedProfile(String scenario, int jobCount, int workerCount) {
        Long documentVersionId = insertDocumentVersion(scenario);
        List<Long> jobIds = insertPendingJobs(documentVersionId, jobCount);
        List<Long> workerIds = insertActiveWorkers(workerCount, scenario);
        return new ProfileSeed(documentVersionId, jobIds, workerIds);
    }

    private void resetProfileData() {
        // FK 역순으로 Benchmark가 생성한 Claim 데이터만 제거하고 Flyway Seed 모델은 유지한다.
        jdbcTemplate.update("DELETE FROM indexing_events");
        jdbcTemplate.update("DELETE FROM embedding_job_attempts");
        jdbcTemplate.update("DELETE FROM embedding_jobs");
        jdbcTemplate.update("DELETE FROM worker_nodes");
        jdbcTemplate.update("DELETE FROM document_versions WHERE title_snapshot LIKE 'Claim Performance%'");
        jdbcTemplate.update("DELETE FROM documents WHERE title LIKE 'Claim Performance%'");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'claim-performance-%'");
    }

    private Long insertDocumentVersion(String scenario) {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Claim Performance User', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "claim-performance-" + suffix + "@example.com");
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
            """, Long.class, userId, "Claim Performance " + scenario + " " + suffix);
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
            """, Long.class, documentId, "Claim Performance " + scenario, userId);
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
                   TIMESTAMP '2026-07-26 00:00:00' + series_no * INTERVAL '1 microsecond',
                   TIMESTAMP '2026-07-26 00:00:00' + series_no * INTERVAL '1 microsecond'
            FROM embedding_models model
            CROSS JOIN generate_series(1, ?) AS series(series_no)
            WHERE model.is_active = TRUE
              AND model.is_searchable = TRUE
            """, documentVersionId, jobCount);

        assertThat(insertedCount).isEqualTo(jobCount);
        return jdbcTemplate.queryForList(
            "SELECT id FROM embedding_jobs WHERE document_version_id = ? ORDER BY id",
            Long.class,
            documentVersionId
        );
    }

    private List<Long> insertActiveWorkers(int workerCount, String scenario) {
        String normalizedScenario = scenario.replaceAll("[^a-zA-Z0-9]", "");
        String instancePrefix = "cp-" + normalizedScenario.substring(0, Math.min(16, normalizedScenario.length()))
            + "-" + UUID.randomUUID().toString().substring(0, 8);
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

    private ProfileExecution executeWorkers(List<Long> workerIds, int jobCount) throws Exception {
        CountDownLatch readyLatch = new CountDownLatch(workerIds.size());
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(workerIds.size(), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("job-claim-performance-" + workerThreadSequence.incrementAndGet());
            return thread;
        });

        List<Future<WorkerClaimResult>> futures = new ArrayList<>();
        try {
            // 1. Executor Thread 수와 Worker 수를 일치시켜 모든 Task가 Start Gate에 도달할 수 있게 한다.
            for (Long workerId : workerIds) {
                futures.add(executorService.submit(() -> {
                    readyLatch.countDown();
                    try {
                        awaitLatch(startLatch, PROFILE_TIMEOUT_SECONDS, "성능 Profile 시작");
                        return claimUntilQueueIsEmpty(workerId, jobCount);
                    } catch (Throwable failure) {
                        return new WorkerClaimResult(workerId, List.of(), List.of(), failure);
                    }
                }));
            }

            // 2. 일부 Worker가 먼저 Queue를 소진하지 않도록 모든 Thread 준비를 확인한다.
            assertThat(readyLatch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("모든 성능 Profile Worker가 제한 시간 안에 준비되어야 한다")
                .isTrue();

            // 3. 공통 Gate 개방 직전부터 마지막 Future 완료까지를 Queue 소진 Wall Clock으로 측정한다.
            long startedAt = System.nanoTime();
            startLatch.countDown();
            List<WorkerClaimResult> workerResults = awaitFuturesWithinSharedDeadline(futures);
            long elapsedNanos = System.nanoTime() - startedAt;

            return new ProfileExecution(workerResults, elapsedNanos);
        } finally {
            startLatch.countDown();
            shutdownExecutor(executorService);
        }
    }

    private WorkerClaimResult claimUntilQueueIsEmpty(Long workerId, int jobCount) {
        List<ClaimSample> successfulClaims = new ArrayList<>();
        List<Long> emptyLatenciesNanos = new ArrayList<>(1);
        try {
            while (successfulClaims.size() <= jobCount) {
                long startedAt = System.nanoTime();
                Optional<ClaimedEmbeddingJobResponse> claimedJob = embeddingJobClaimService.claim(workerId);
                long elapsedNanos = System.nanoTime() - startedAt;

                if (claimedJob.isEmpty()) {
                    emptyLatenciesNanos.add(elapsedNanos);
                    return new WorkerClaimResult(
                        workerId,
                        List.copyOf(successfulClaims),
                        List.copyOf(emptyLatenciesNanos),
                        null
                    );
                }
                successfulClaims.add(new ClaimSample(claimedJob.get(), elapsedNanos));
            }
            return new WorkerClaimResult(
                workerId,
                List.copyOf(successfulClaims),
                List.copyOf(emptyLatenciesNanos),
                new IllegalStateException("한 Worker의 Claim 수가 준비 Job 수를 초과했습니다.")
            );
        } catch (Throwable failure) {
            return new WorkerClaimResult(
                workerId,
                List.copyOf(successfulClaims),
                List.copyOf(emptyLatenciesNanos),
                failure
            );
        }
    }

    private List<WorkerClaimResult> awaitFuturesWithinSharedDeadline(
        List<Future<WorkerClaimResult>> futures
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROFILE_TIMEOUT_SECONDS);
        List<WorkerClaimResult> results = new ArrayList<>(futures.size());

        for (Future<WorkerClaimResult> future : futures) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("Claim 성능 Profile이 전체 제한 시간을 초과했습니다.");
            }
            try {
                results.add(future.get(remainingNanos, TimeUnit.NANOSECONDS));
            } catch (ExecutionException exception) {
                throw new IllegalStateException(
                    "성능 Profile Worker Thread에서 예상하지 못한 오류가 발생했습니다.",
                    exception.getCause()
                );
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
            .as("성능 Profile Executor가 강제 종료 후 제한 시간 안에 종료되어야 한다")
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

    private void awaitDatabaseStatsFlush() {
        try {
            TimeUnit.MILLISECONDS.sleep(DATABASE_STATS_SETTLE_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PostgreSQL 누적 통계 반영 대기 중 Thread가 중단되었습니다.", exception);
        }
    }

    private ConsistencySummary assertProfileConsistency(ProfileSeed seed, ProfileExecution execution) {
        List<String> failures = execution.workerResults().stream()
            .filter(result -> result.failure() != null)
            .map(result -> formatFailure(result.workerId(), result.failure()))
            .toList();
        assertThat(failures).as("Worker Thread 오류가 없어야 한다").isEmpty();

        List<ClaimedEmbeddingJobResponse> claimedJobs = execution.workerResults().stream()
            .flatMap(result -> result.successfulClaims().stream())
            .map(ClaimSample::claimedJob)
            .toList();

        // 1. 성공 응답의 개수와 ID 집합을 비교해 중복 응답으로 가려진 Queue 누락을 함께 탐지한다.
        assertThat(claimedJobs).hasSize(seed.jobIds().size());
        assertThat(claimedJobs).extracting(ClaimedEmbeddingJobResponse::jobId).doesNotHaveDuplicates();
        assertThat(new HashSet<>(claimedJobs.stream().map(ClaimedEmbeddingJobResponse::jobId).toList()))
            .isEqualTo(new HashSet<>(seed.jobIds()));

        // 2. 모든 응답이 준비된 Worker와 서로 다른 UUID Token을 가져야 한다.
        assertThat(claimedJobs).extracting(ClaimedEmbeddingJobResponse::workerId)
            .allMatch(seed.workerIds()::contains);
        assertThat(claimedJobs).extracting(ClaimedEmbeddingJobResponse::claimToken)
            .doesNotHaveDuplicates()
            .allSatisfy(this::assertThatUuid);

        // 3. 응답만 성공하고 DB 소유권 Commit이 누락되는 부분 성공을 Job별 Snapshot 비교로 차단한다.
        Map<Long, JobOwnershipSnapshot> snapshots = findOwnershipSnapshots(seed.documentVersionId());
        assertThat(snapshots).hasSize(seed.jobIds().size());
        claimedJobs.forEach(claimedJob -> assertOwnershipMatches(
            claimedJob,
            snapshots.get(claimedJob.jobId())
        ));

        // 4. 최종 Queue와 이벤트 집계를 확인해 모든 Job이 정확히 한 번 PROCESSING으로 전환됐음을 확정한다.
        ConsistencySummary consistencySummary = new ConsistencySummary(
            failures.size(),
            countJobsByStatus(seed.documentVersionId(), "PENDING"),
            countJobsByStatus(seed.documentVersionId(), "PROCESSING"),
            countJobsWithIncompleteOwnership(seed.documentVersionId()),
            countDuplicateClaimTokens(seed.documentVersionId()),
            countLockedEvents(seed.documentVersionId()),
            countJobsWithInvalidLockedEventCount(seed.documentVersionId())
        );
        assertThat(consistencySummary.pendingJobs()).isZero();
        assertThat(consistencySummary.processingJobs()).isEqualTo(seed.jobIds().size());
        assertThat(consistencySummary.incompleteOwnershipJobs()).isZero();
        assertThat(consistencySummary.duplicateClaimTokens()).isZero();
        assertThat(consistencySummary.lockedEvents()).isEqualTo(seed.jobIds().size());
        assertThat(consistencySummary.invalidLockedEventJobs()).isZero();
        return consistencySummary;
    }

    private Map<Long, JobOwnershipSnapshot> findOwnershipSnapshots(Long documentVersionId) {
        return jdbcTemplate.query("""
            SELECT id,
                   status,
                   locked_by_worker_id,
                   claim_token,
                   locked_at,
                   lock_expires_at
            FROM embedding_jobs
            WHERE document_version_id = ?
            ORDER BY id
            """, (resultSet, rowNumber) -> new JobOwnershipSnapshot(
            resultSet.getLong("id"),
            resultSet.getString("status"),
            resultSet.getObject("locked_by_worker_id", Long.class),
            resultSet.getString("claim_token"),
            resultSet.getObject("locked_at", LocalDateTime.class),
            resultSet.getObject("lock_expires_at", LocalDateTime.class)
        ), documentVersionId).stream().collect(Collectors.toMap(
            JobOwnershipSnapshot::jobId,
            Function.identity()
        ));
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

    private int countJobsByStatus(Long documentVersionId, String status) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM embedding_jobs
            WHERE document_version_id = ?
              AND status = ?
            """, Integer.class, documentVersionId, status);
    }

    private int countJobsWithIncompleteOwnership(Long documentVersionId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM embedding_jobs
            WHERE document_version_id = ?
              AND status = 'PROCESSING'
              AND (
                    locked_by_worker_id IS NULL
                 OR claim_token IS NULL
                 OR locked_at IS NULL
                 OR lock_expires_at IS NULL
              )
            """, Integer.class, documentVersionId);
    }

    private int countDuplicateClaimTokens(Long documentVersionId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM (
                SELECT claim_token
                FROM embedding_jobs
                WHERE document_version_id = ?
                GROUP BY claim_token
                HAVING COUNT(*) > 1
            ) duplicate_token
            """, Integer.class, documentVersionId);
    }

    private int countLockedEvents(Long documentVersionId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM indexing_events event
            JOIN embedding_jobs job ON job.id = event.embedding_job_id
            WHERE job.document_version_id = ?
              AND event.event_type = 'LOCKED'
            """, Integer.class, documentVersionId);
    }

    private int countJobsWithInvalidLockedEventCount(Long documentVersionId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM (
                SELECT job.id
                FROM embedding_jobs job
                LEFT JOIN indexing_events event
                       ON event.embedding_job_id = job.id
                      AND event.event_type = 'LOCKED'
                WHERE job.document_version_id = ?
                GROUP BY job.id
                HAVING COUNT(event.id) <> 1
            ) invalid_job
            """, Integer.class, documentVersionId);
    }

    private ProfileRunSummary summarizeProfile(
        int workerCount,
        int repetition,
        ProfileExecution execution,
        ConsistencySummary consistencySummary,
        WaitSummary waitSummary,
        DatabaseStats transactionDelta
    ) {
        List<Long> successfulLatencies = execution.workerResults().stream()
            .flatMap(result -> result.successfulClaims().stream())
            .map(ClaimSample::latencyNanos)
            .toList();
        List<Long> emptyLatencies = execution.workerResults().stream()
            .flatMap(result -> result.emptyLatenciesNanos().stream())
            .toList();
        List<Integer> workerClaimCounts = execution.workerResults().stream()
            .map(result -> result.successfulClaims().size())
            .sorted()
            .toList();

        LatencySummary latencySummary = LatencySummary.from(successfulLatencies);
        LatencySummary emptyLatencySummary = LatencySummary.from(emptyLatencies);
        WorkerDistributionSummary distribution = WorkerDistributionSummary.from(workerClaimCounts);
        double elapsedSeconds = execution.elapsedNanos() / 1_000_000_000.0;

        return new ProfileRunSummary(
            workerCount,
            DB_CONNECTION_POOL_SIZE,
            successfulLatencies.size(),
            consistencySummary.workerErrors(),
            repetition,
            MEASURED_REPETITIONS,
            nanosToMillis(execution.elapsedNanos()),
            successfulLatencies.size() / elapsedSeconds,
            latencySummary.p50Millis(),
            latencySummary.p95Millis(),
            latencySummary.p99Millis(),
            latencySummary.maxMillis(),
            emptyLatencies.size(),
            emptyLatencySummary.p50Millis(),
            emptyLatencySummary.maxMillis(),
            distribution.min(),
            distribution.median(),
            distribution.max(),
            waitSummary.hikariMaxActive(),
            waitSummary.hikariMaxAwaiting(),
            waitSummary.hikariAwaitingSamples(),
            waitSummary.postgresMaxLockWaiters(),
            waitSummary.postgresLockWaitSamples(),
            waitSummary.sampleCount(),
            successfulLatencies.size() + emptyLatencies.size(),
            transactionDelta.commits(),
            DB_CONNECTION_POOL_SIZE,
            transactionDelta.rollbacks(),
            transactionDelta.deadlocks(),
            consistencySummary.pendingJobs(),
            consistencySummary.processingJobs(),
            consistencySummary.incompleteOwnershipJobs(),
            consistencySummary.duplicateClaimTokens(),
            consistencySummary.lockedEvents(),
            consistencySummary.invalidLockedEventJobs()
        );
    }

    private DatabaseStats readDatabaseStats(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT xact_commit, xact_rollback, deadlocks
            FROM pg_stat_database
            WHERE datname = current_database()
            """);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).as("현재 Database 통계 Row가 존재해야 한다").isTrue();
            return new DatabaseStats(
                resultSet.getLong("xact_commit"),
                resultSet.getLong("xact_rollback"),
                resultSet.getLong("deadlocks")
            );
        }
    }

    private void clearDatabaseStatsSnapshot(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_stat_clear_snapshot()")) {
            statement.execute();
        }
    }

    private void forceHikariBackendStatsFlush() throws SQLException {
        List<Connection> poolConnections = new ArrayList<>(DB_CONNECTION_POOL_SIZE);
        try {
            // 모든 Connection을 동시에 빌려 같은 물리 Backend를 반복해서 선택하는 것을 방지한다.
            for (int index = 0; index < DB_CONNECTION_POOL_SIZE; index++) {
                poolConnections.add(dataSource.getConnection());
            }
            for (Connection connection : poolConnections) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                    statement.execute();
                }
            }
        } finally {
            for (int index = poolConnections.size() - 1; index >= 0; index--) {
                poolConnections.get(index).close();
            }
        }
    }

    private Connection openMonitoringConnection() throws SQLException {
        HikariDataSource hikariDataSource = hikariDataSource();
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", hikariDataSource.getUsername());
        connectionProperties.setProperty("password", hikariDataSource.getPassword());
        connectionProperties.setProperty("ApplicationName", MONITOR_APPLICATION_NAME);
        return DriverManager.getConnection(hikariDataSource.getJdbcUrl(), connectionProperties);
    }

    private HikariDataSource hikariDataSource() throws SQLException {
        return dataSource.unwrap(HikariDataSource.class);
    }

    private Map<String, Object> collectEnvironmentFingerprint() {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("executedAtUtc", OffsetDateTime.now(ZoneOffset.UTC).toString());
        fingerprint.put("commitHash", resolveCommitHash());
        fingerprint.put("workingTreeDirty", !runCommand("git", "status", "--short", "--untracked-files=no").isBlank());
        fingerprint.put("hostOs", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        fingerprint.put("hostArchitecture", System.getProperty("os.arch"));
        fingerprint.put("cpuModel", resolveCpuModel());
        fingerprint.put("cpuCores", Runtime.getRuntime().availableProcessors());
        fingerprint.put("dockerCpuAllocation", environmentValue("CLAIM_PERFORMANCE_DOCKER_CPU", "not-provided"));
        fingerprint.put("dockerMemoryAllocation", environmentValue("CLAIM_PERFORMANCE_DOCKER_MEMORY", "not-provided"));
        fingerprint.put("containerPlatform", environmentValue("CLAIM_PERFORMANCE_CONTAINER_PLATFORM", "not-provided"));
        fingerprint.put("databaseVersion", jdbcTemplate.queryForObject("SELECT version()", String.class));
        fingerprint.put("postgresServerVersion", jdbcTemplate.queryForObject("SHOW server_version", String.class));
        fingerprint.put("pgvectorVersion", jdbcTemplate.queryForObject(
            "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
            String.class
        ));
        fingerprint.put("databaseSsl", jdbcTemplate.queryForObject(
            "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()",
            Boolean.class
        ));
        fingerprint.put("configuredSslMode", environmentValue("DB_SSLMODE", "disable"));
        fingerprint.put("javaVersion", System.getProperty("java.version"));
        fingerprint.put("jvm", ManagementFactory.getRuntimeMXBean().getVmName());
        fingerprint.put("jvmMaxHeapBytes", Runtime.getRuntime().maxMemory());
        fingerprint.put("springBootVersion", SpringBootVersion.getVersion());
        fingerprint.put("schema", TEST_SCHEMA);
        fingerprint.put("hikariPoolSize", DB_CONNECTION_POOL_SIZE);
        fingerprint.put("hikariConnectionTimeoutMillis", hikariDataSourceConnectionTimeout());
        fingerprint.put("workerCounts", WORKER_COUNTS);
        fingerprint.put("warmUpJobCount", WARM_UP_JOB_COUNT);
        fingerprint.put("measuredJobCount", MEASURED_JOB_COUNT);
        fingerprint.put("repetitions", MEASURED_REPETITIONS);
        fingerprint.put("samplingIntervalMillis", SAMPLING_INTERVAL_MILLIS);
        return fingerprint;
    }

    private long hikariDataSourceConnectionTimeout() {
        try {
            return hikariDataSource().getConnectionTimeout();
        } catch (SQLException exception) {
            throw new IllegalStateException("Hikari Connection Timeout을 읽을 수 없습니다.", exception);
        }
    }

    private String resolveCommitHash() {
        String suppliedCommit = environmentValue("CLAIM_PERFORMANCE_COMMIT", "");
        if (!suppliedCommit.isBlank()) {
            return suppliedCommit;
        }
        String githubCommit = environmentValue("GITHUB_SHA", "");
        if (!githubCommit.isBlank()) {
            return githubCommit;
        }
        return runCommand("git", "rev-parse", "HEAD");
    }

    private String resolveCpuModel() {
        String suppliedCpuModel = environmentValue("CLAIM_PERFORMANCE_CPU_MODEL", "");
        if (!suppliedCpuModel.isBlank()) {
            return suppliedCpuModel;
        }

        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            return runCommand("sysctl", "-n", "machdep.cpu.brand_string");
        }

        Path cpuInfo = Path.of("/proc/cpuinfo");
        if (Files.isReadable(cpuInfo)) {
            try (var cpuInfoLines = Files.lines(cpuInfo)) {
                return cpuInfoLines
                    .filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .findFirst()
                    .orElse("unavailable");
            } catch (IOException exception) {
                return "unavailable";
            }
        }
        return "unavailable";
    }

    private String runCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unavailable";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 ? output : "unavailable";
        } catch (IOException exception) {
            return "unavailable";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "unavailable";
        }
    }

    private void logJson(String prefix, Object result) throws JsonProcessingException {
        log.info("{} {}", prefix, objectMapper.writeValueAsString(result));
    }

    private String formatFailure(Long workerId, Throwable failure) {
        return "workerId=" + workerId
            + ", type=" + failure.getClass().getSimpleName()
            + ", message=" + failure.getMessage();
    }

    private void assertThatUuid(String value) {
        assertThat(value).isNotBlank();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String environmentValue(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }

    private static int positiveIntegerProperty(String name, int defaultValue) {
        int value = Integer.getInteger(name, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 값은 0보다 커야 합니다.");
        }
        return value;
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        long value = Long.getLong(name, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 값은 0보다 커야 합니다.");
        }
        return value;
    }

    private static List<Integer> positiveIntegerListProperty(String name, List<Integer> defaultValue) {
        String rawValue = System.getProperty(name);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }

        List<Integer> values = List.of(rawValue.split(",")).stream()
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(Integer::parseInt)
            .toList();
        if (values.isEmpty() || values.stream().anyMatch(value -> value <= 0)) {
            throw new IllegalArgumentException(name + " 값은 양의 정수 목록이어야 합니다.");
        }
        return values;
    }

    /**
     * Worker Hikari Pool과 PostgreSQL Lock 대기를 일정 간격으로 관찰하는 Benchmark 전용 Sampler.
     *
     * <p>Monitoring SQL은 별도 Driver Connection의 단일 Transaction에서 실행해 Worker Pool을 점유하지
     * 않으며, 반복 SELECT가 pg_stat_database Commit 수를 오염시키는 것도 제한한다.
     */
    private final class WaitingSampler implements AutoCloseable {

        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger sampleCount = new AtomicInteger();
        private final AtomicInteger hikariMaxActive = new AtomicInteger();
        private final AtomicInteger hikariMaxAwaiting = new AtomicInteger();
        private final AtomicInteger hikariAwaitingSamples = new AtomicInteger();
        private final AtomicInteger postgresMaxLockWaiters = new AtomicInteger();
        private final AtomicInteger postgresLockWaitSamples = new AtomicInteger();
        private final CountDownLatch firstSampleLatch = new CountDownLatch(1);

        private ExecutorService executorService;
        private Future<?> samplerFuture;
        private Connection monitoringConnection;
        private PreparedStatement lockWaiterStatement;
        private boolean stopped;

        void start() throws Exception {
            monitoringConnection = openMonitoringConnection();
            monitoringConnection.setReadOnly(true);
            monitoringConnection.setAutoCommit(false);
            lockWaiterStatement = monitoringConnection.prepareStatement(LOCK_WAITER_COUNT_SQL);
            lockWaiterStatement.setString(1, WORKER_APPLICATION_NAME);

            HikariPoolMXBean poolMxBean = hikariDataSource().getHikariPoolMXBean();
            executorService = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("job-claim-performance-sampler");
                return thread;
            });

            // 첫 Sample 완료를 확인한 뒤 Worker를 시작해 짧은 Profile도 관측 없이 끝나지 않게 한다.
            running.set(true);
            samplerFuture = executorService.submit(() -> sampleUntilStopped(poolMxBean));
            if (!firstSampleLatch.await(SAMPLER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new TimeoutException("성능 대기 상태 Sampler가 제한 시간 안에 시작되지 않았습니다.");
            }
            throwIfSamplingFailed();
        }

        WaitSummary stopAndSummarize() throws Exception {
            if (stopped) {
                return currentSummary();
            }

            stopped = true;
            running.set(false);
            if (samplerFuture != null) {
                samplerFuture.get(SAMPLER_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            if (monitoringConnection != null) {
                monitoringConnection.commit();
            }
            throwIfSamplingFailed();
            return currentSummary();
        }

        private void sampleUntilStopped(HikariPoolMXBean poolMxBean) {
            try {
                while (running.get()) {
                    sample(poolMxBean);
                    firstSampleLatch.countDown();
                    TimeUnit.MILLISECONDS.sleep(SAMPLING_INTERVAL_MILLIS);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, exception);
                firstSampleLatch.countDown();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
                firstSampleLatch.countDown();
            }
        }

        private void sample(HikariPoolMXBean poolMxBean) throws SQLException {
            int activeConnections = poolMxBean.getActiveConnections();
            int awaitingConnections = poolMxBean.getThreadsAwaitingConnection();
            int lockWaiters;

            try (ResultSet resultSet = lockWaiterStatement.executeQuery()) {
                assertThat(resultSet.next()).as("PostgreSQL Lock 대기 집계 Row가 존재해야 한다").isTrue();
                lockWaiters = resultSet.getInt(1);
            }

            sampleCount.incrementAndGet();
            hikariMaxActive.accumulateAndGet(activeConnections, Math::max);
            hikariMaxAwaiting.accumulateAndGet(awaitingConnections, Math::max);
            postgresMaxLockWaiters.accumulateAndGet(lockWaiters, Math::max);
            if (awaitingConnections > 0) {
                hikariAwaitingSamples.incrementAndGet();
            }
            if (lockWaiters > 0) {
                postgresLockWaitSamples.incrementAndGet();
            }
        }

        private WaitSummary currentSummary() {
            return new WaitSummary(
                hikariMaxActive.get(),
                hikariMaxAwaiting.get(),
                hikariAwaitingSamples.get(),
                postgresMaxLockWaiters.get(),
                postgresLockWaitSamples.get(),
                sampleCount.get()
            );
        }

        private void throwIfSamplingFailed() {
            Throwable samplingFailure = failure.get();
            if (samplingFailure != null) {
                throw new IllegalStateException("성능 대기 상태 Sampling에 실패했습니다.", samplingFailure);
            }
        }

        @Override
        public void close() throws Exception {
            try {
                stopAndSummarize();
            } finally {
                if (lockWaiterStatement != null) {
                    lockWaiterStatement.close();
                }
                if (monitoringConnection != null) {
                    monitoringConnection.close();
                }
                if (executorService != null) {
                    executorService.shutdownNow();
                    executorService.awaitTermination(SAMPLER_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            }
        }
    }

    /**
     * 한 Profile 실행에 사용할 문서 버전과 초기 Job·Worker ID 집합.
     */
    private record ProfileSeed(
        Long documentVersionId,
        List<Long> jobIds,
        List<Long> workerIds
    ) {
    }

    /**
     * 성공한 Claim 응답과 Service Proxy 호출 지연을 같은 표본으로 보존하는 값 객체.
     */
    private record ClaimSample(
        ClaimedEmbeddingJobResponse claimedJob,
        long latencyNanos
    ) {
    }

    /**
     * 한 Worker가 Queue 종료까지 수집한 성공·빈 Queue 지연과 오류를 묶는 값 객체.
     */
    private record WorkerClaimResult(
        Long workerId,
        List<ClaimSample> successfulClaims,
        List<Long> emptyLatenciesNanos,
        Throwable failure
    ) {
    }

    /**
     * 공통 Start Gate 이후의 Worker별 결과와 Queue 소진 Wall Clock을 보존하는 값 객체.
     */
    private record ProfileExecution(
        List<WorkerClaimResult> workerResults,
        long elapsedNanos
    ) {
    }

    /**
     * Claim 응답과 Commit된 Job 소유권 Row를 비교하기 위한 조회 전용 Snapshot.
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

    /**
     * Sampler가 관찰한 Hikari와 PostgreSQL 대기의 최대값 및 발생 Sample 수.
     */
    private record WaitSummary(
        int hikariMaxActive,
        int hikariMaxAwaiting,
        int hikariAwaitingSamples,
        int postgresMaxLockWaiters,
        int postgresLockWaitSamples,
        int sampleCount
    ) {
    }

    /**
     * Profile 종료 후 Queue, 소유권, Token, LOCKED 이벤트의 실제 DB 집계값.
     */
    private record ConsistencySummary(
        int workerErrors,
        int pendingJobs,
        int processingJobs,
        int incompleteOwnershipJobs,
        int duplicateClaimTokens,
        int lockedEvents,
        int invalidLockedEventJobs
    ) {
    }

    /**
     * pg_stat_database 누적 Transaction과 Deadlock 값의 Snapshot.
     */
    private record DatabaseStats(
        long commits,
        long rollbacks,
        long deadlocks
    ) {

        DatabaseStats minus(DatabaseStats before) {
            return new DatabaseStats(
                commits - before.commits,
                rollbacks - before.rollbacks,
                deadlocks - before.deadlocks
            );
        }
    }

    /**
     * Nanosecond 표본을 nearest-rank percentile 기반 Millisecond 통계로 변환한 값 객체.
     */
    private record LatencySummary(
        double p50Millis,
        double p95Millis,
        double p99Millis,
        double maxMillis
    ) {

        static LatencySummary from(List<Long> latenciesNanos) {
            if (latenciesNanos.isEmpty()) {
                return new LatencySummary(0.0, 0.0, 0.0, 0.0);
            }

            List<Long> sorted = latenciesNanos.stream().sorted().toList();
            return new LatencySummary(
                nanosToMillis(nearestRank(sorted, 0.50)),
                nanosToMillis(nearestRank(sorted, 0.95)),
                nanosToMillis(nearestRank(sorted, 0.99)),
                nanosToMillis(sorted.get(sorted.size() - 1))
            );
        }

        private static long nearestRank(List<Long> sorted, double percentile) {
            int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(index);
        }
    }

    /**
     * Worker별 성공 Claim 수의 최소·중앙·최대 분포.
     */
    private record WorkerDistributionSummary(
        int min,
        double median,
        int max
    ) {

        static WorkerDistributionSummary from(List<Integer> sortedClaimCounts) {
            assertThat(sortedClaimCounts).isNotEmpty();
            return new WorkerDistributionSummary(
                sortedClaimCounts.get(0),
                median(sortedClaimCounts),
                sortedClaimCounts.get(sortedClaimCounts.size() - 1)
            );
        }

        private static double median(List<Integer> sortedValues) {
            int middle = sortedValues.size() / 2;
            if (sortedValues.size() % 2 == 1) {
                return sortedValues.get(middle);
            }
            return (sortedValues.get(middle - 1) + sortedValues.get(middle)) / 2.0;
        }
    }

    /**
     * 한 Worker·반복 Profile의 성능, 자원 대기, Transaction 관찰값을 직렬화하는 결과 값 객체.
     */
    private record ProfileRunSummary(
        int workerCount,
        int poolSize,
        int jobCount,
        int workerErrors,
        int repetition,
        int totalRepetitions,
        double queueElapsedMillis,
        double claimsPerSecond,
        double latencyP50Millis,
        double latencyP95Millis,
        double latencyP99Millis,
        double latencyMaxMillis,
        int emptyQueueCalls,
        double emptyLatencyP50Millis,
        double emptyLatencyMaxMillis,
        int workerMinClaims,
        double workerMedianClaims,
        int workerMaxClaims,
        int hikariMaxActive,
        int hikariMaxAwaiting,
        int hikariAwaitingSamples,
        int postgresMaxLockWaiters,
        int postgresLockWaitSamples,
        int samplerSamples,
        int completedClaimTransactions,
        long observedDatabaseCommits,
        int statsBoundaryConnections,
        long observedRollbacks,
        long observedDeadlocks,
        int finalPendingJobs,
        int finalProcessingJobs,
        int incompleteOwnershipJobs,
        int duplicateClaimTokens,
        int lockedEvents,
        int invalidLockedEventJobs
    ) {
    }

    /**
     * 같은 Worker 수로 반복한 Profile의 주요 관찰값 중앙값.
     */
    private record ProfileMedianSummary(
        int workerCount,
        int poolSize,
        int jobCount,
        int repetitions,
        double queueElapsedMillis,
        double claimsPerSecond,
        double latencyP50Millis,
        double latencyP95Millis,
        double latencyP99Millis,
        double latencyMaxMillis,
        double hikariMaxAwaiting,
        double postgresMaxLockWaiters
    ) {

        static ProfileMedianSummary from(List<ProfileRunSummary> summaries) {
            assertThat(summaries).isNotEmpty();
            ProfileRunSummary first = summaries.get(0);
            return new ProfileMedianSummary(
                first.workerCount(),
                first.poolSize(),
                first.jobCount(),
                summaries.size(),
                median(summaries, ProfileRunSummary::queueElapsedMillis),
                median(summaries, ProfileRunSummary::claimsPerSecond),
                median(summaries, ProfileRunSummary::latencyP50Millis),
                median(summaries, ProfileRunSummary::latencyP95Millis),
                median(summaries, ProfileRunSummary::latencyP99Millis),
                median(summaries, ProfileRunSummary::latencyMaxMillis),
                median(summaries, summary -> (double) summary.hikariMaxAwaiting()),
                median(summaries, summary -> (double) summary.postgresMaxLockWaiters())
            );
        }

        private static double median(
            List<ProfileRunSummary> summaries,
            Function<ProfileRunSummary, Double> extractor
        ) {
            List<Double> sortedValues = summaries.stream()
                .map(extractor)
                .sorted(Comparator.naturalOrder())
                .toList();
            int middle = sortedValues.size() / 2;
            if (sortedValues.size() % 2 == 1) {
                return sortedValues.get(middle);
            }
            return (sortedValues.get(middle - 1) + sortedValues.get(middle)) / 2.0;
        }
    }
}
