package com.opensource.docgrid.domain.worker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseRecoveryService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseRecoveryService.RecoveryResult;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseService;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool.WorkerExecutionSlot;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerJobPollingScheduler;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerLifecycleManager;
import com.opensource.docgrid.domain.worker.service.WorkerIndexingPipeline;
import com.opensource.docgrid.domain.worker.service.WorkerLeaseRenewalHandle;
import com.opensource.docgrid.domain.worker.service.WorkerLeaseRenewalManager;

/**
 * 실제 PostgreSQL에서 Worker Polling 슬롯과 Lease 갱신·복구의 교차 계층 동시성 경계를 검증한다.
 *
 * <p>Poller는 실제 Claim Service를 사용하되 외부 I/O Pipeline은 제어 가능한 Mock으로 대체한다. Lease
 * 시나리오는 실제 갱신과 복구 Transaction을 사용해 한 Job의 DB 소유권이 중복 처리되지 않는지 확인한다.
 */
@Tag("integration")
@Tag("claim-concurrency")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Worker 오케스트레이션 PostgreSQL 통합 테스트")
class WorkerOrchestrationIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_worker_orchestration_integration_test";
    private static final int MAX_CONCURRENCY = 2;
    private static final long TIMEOUT_SECONDS = 10;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EmbeddingJobClaimService claimService;
    @Autowired private EmbeddingJobLeaseService leaseService;
    @Autowired private EmbeddingJobLeaseRecoveryService recoveryService;
    @Autowired private IndexingWorkerProperties workerProperties;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-worker-orchestration-integration-test-secret-key-2026");
        registry.add("indexing.worker.dead-threshold", () -> "10m");
        registry.add("indexing.worker.lease-duration", () -> "2s");
        registry.add("indexing.worker.lease-renewal-interval", () -> "50ms");
        registry.add("indexing.worker.retry-initial-delay", () -> "10s");
        registry.add("indexing.worker.retry-max-delay", () -> "5m");
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                indexing_events,
                embedding_job_attempts,
                embedding_jobs,
                document_versions,
                documents,
                worker_nodes,
                users
            RESTART IDENTITY CASCADE
            """);
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("여러 Poller가 한 Job을 경쟁해도 실제 Claim과 실행 제출은 한 번만 발생한다")
    void poll_concurrentWorkersClaimOneJobExactlyOnce() throws Exception {
        TestContext context = insertContext("single-claim");
        Long jobId = insertPendingJobs(context, 1).get(0);
        Long firstWorkerId = insertActiveWorker("poller-a");
        Long secondWorkerId = insertActiveWorker("poller-b");
        WorkerIndexingPipeline pipeline = mock(WorkerIndexingPipeline.class);
        CountDownLatch executed = new CountDownLatch(1);
        doAnswer(invocation -> {
            WorkerExecutionSlot slot = invocation.getArgument(1);
            try {
                executed.countDown();
            } finally {
                slot.close();
            }
            return null;
        }).when(pipeline).execute(any(ClaimedEmbeddingJobResponse.class), any(WorkerExecutionSlot.class));

        ThreadPoolExecutor firstJobExecutor = newJobExecutor(1);
        ThreadPoolExecutor secondJobExecutor = newJobExecutor(1);
        WorkerJobPollingScheduler firstPoller = newPoller(firstWorkerId, firstJobExecutor, pipeline, 1);
        WorkerJobPollingScheduler secondPoller = newPoller(secondWorkerId, secondJobExecutor, pipeline, 1);

        try {
            // 1. 두 Poller가 같은 시점에 실제 Claim Transaction을 시작하도록 Barrier에서 맞춘다.
            runPollersConcurrently(List.of(firstPoller, secondPoller));

            // 2. 유일한 Claim만 Pipeline에 제출됐는지와 DB 소유권을 함께 확인한다.
            assertThat(executed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            then(pipeline).should(times(1)).execute(
                any(ClaimedEmbeddingJobResponse.class),
                any(WorkerExecutionSlot.class)
            );
            assertThat(queryInteger(
                "SELECT COUNT(*) FROM embedding_jobs WHERE id = ? AND status = 'PROCESSING'",
                jobId
            )).isOne();
            assertThat(queryLong(
                "SELECT locked_by_worker_id FROM embedding_jobs WHERE id = ?",
                jobId
            )).isIn(firstWorkerId, secondWorkerId);
            assertThat(eventCount(jobId, "LOCKED")).isOne();
        } finally {
            shutdownExecutor(firstJobExecutor);
            shutdownExecutor(secondJobExecutor);
        }
    }

    @Test
    @DisplayName("한 Worker는 실행 슬롯 수보다 많은 Job을 PROCESSING으로 Claim하지 않는다")
    void poll_limitsProcessingJobsToLocalExecutionCapacity() throws Exception {
        TestContext context = insertContext("slot-capacity");
        insertPendingJobs(context, 5);
        Long workerId = insertActiveWorker("capacity-poller");
        WorkerExecutionSlotPool slotPool = new WorkerExecutionSlotPool(MAX_CONCURRENCY);
        WorkerIndexingPipeline pipeline = mock(WorkerIndexingPipeline.class);
        CountDownLatch started = new CountDownLatch(MAX_CONCURRENCY);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            WorkerExecutionSlot slot = invocation.getArgument(1);
            try {
                started.countDown();
                if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Worker Pipeline 해제 대기가 제한 시간을 초과했습니다.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Worker Pipeline 대기 중 Thread가 중단되었습니다.", exception);
            } finally {
                slot.close();
            }
            return null;
        }).when(pipeline).execute(any(ClaimedEmbeddingJobResponse.class), any(WorkerExecutionSlot.class));

        ThreadPoolExecutor jobExecutor = newJobExecutor(MAX_CONCURRENCY);
        WorkerJobPollingScheduler poller = newPoller(workerId, jobExecutor, pipeline, slotPool);

        try {
            // 1. 첫 Polling이 두 실행 슬롯을 모두 점유한 상태로 Pipeline을 대기시킨다.
            poller.poll();
            assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(slotPool.getActiveSlots()).isEqualTo(MAX_CONCURRENCY);

            // 2. 슬롯이 없는 동안 다시 Polling해도 추가 Claim이 발생하지 않는지 DB에서 검증한다.
            poller.poll();
            assertThat(countJobs("PROCESSING", workerId)).isEqualTo(MAX_CONCURRENCY);
            assertThat(countJobs("PENDING", null)).isEqualTo(3);
            assertThat(countEvents("LOCKED")).isEqualTo(MAX_CONCURRENCY);

            // 3. 실행이 끝나면 모든 슬롯이 반환돼 다음 Polling이 가능해진다.
            release.countDown();
            awaitAvailableSlots(slotPool, MAX_CONCURRENCY);
        } finally {
            release.countDown();
            shutdownExecutor(jobExecutor);
        }
    }

    @Test
    @DisplayName("활성 실행의 Lease가 갱신되면 원래 만료 시각의 Recovery가 Job을 회수하지 않는다")
    void leaseRenewal_preventsRecoveryAtOriginalExpiry() throws Exception {
        TestContext context = insertContext("renewal-fencing");
        Long jobId = insertPendingJobs(context, 1).get(0);
        Long workerId = insertActiveWorker("renewal-worker");
        ClaimedEmbeddingJobResponse claimedJob = claimService.claim(workerId).orElseThrow();
        LocalDateTime originalExpiry = claimedJob.lockExpiresAt();
        ScheduledThreadPoolExecutor leaseScheduler = new ScheduledThreadPoolExecutor(1);
        WorkerLeaseRenewalManager renewalManager = new WorkerLeaseRenewalManager(
            leaseScheduler,
            leaseService,
            workerProperties
        );
        WorkerLeaseRenewalHandle handle = renewalManager.start(claimedJob);

        try {
            // 1. 실제 Lease Service가 원래 만료 시각보다 뒤로 DB Lease를 연장할 때까지 기다린다.
            LocalDateTime renewedExpiry = awaitRenewedExpiry(jobId, originalExpiry);
            assertThat(renewedExpiry).isAfter(originalExpiry);

            // 2. 원래 Lease는 만료된 논리 시각이어도 갱신된 DB Lease가 Recovery를 차단해야 한다.
            RecoveryResult result = recoveryService.recover(jobId, originalExpiry.plusNanos(1_000));
            assertThat(result.recovered()).isFalse();
            assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", jobId))
                .isEqualTo("PROCESSING");
            assertThat(eventCount(jobId, "LEASE_EXPIRED")).isZero();
        } finally {
            handle.close();
            shutdownLeaseManager(renewalManager, leaseScheduler);
        }
    }

    @Test
    @DisplayName("Lease 갱신을 중단한 뒤 만료 Job을 동시에 복구해도 한 번만 재예약한다")
    void stoppedLeaseRenewal_allowsExactlyOneConcurrentRecovery() throws Exception {
        TestContext context = insertContext("renewal-stopped");
        Long jobId = insertPendingJobs(context, 1).get(0);
        Long workerId = insertActiveWorker("stopped-renewal-worker");
        ClaimedEmbeddingJobResponse claimedJob = claimService.claim(workerId).orElseThrow();
        ScheduledThreadPoolExecutor leaseScheduler = new ScheduledThreadPoolExecutor(1);
        WorkerLeaseRenewalManager renewalManager = new WorkerLeaseRenewalManager(
            leaseScheduler,
            leaseService,
            workerProperties
        );
        WorkerLeaseRenewalHandle handle = renewalManager.start(claimedJob);

        // 1. 한 번 이상 실제 갱신한 뒤 Handle과 Scheduler를 닫아 이후 Lease 연장을 중단한다.
        awaitRenewedExpiry(jobId, claimedJob.lockExpiresAt());
        handle.close();
        shutdownLeaseManager(renewalManager, leaseScheduler);
        LocalDateTime finalExpiry = queryDateTime(
            "SELECT lock_expires_at FROM embedding_jobs WHERE id = ?",
            jobId
        );

        // 2. 같은 만료 시각에 두 Recovery Transaction을 경쟁시켜 유일한 상태 전이만 허용한다.
        List<RecoveryResult> results = recoverConcurrently(jobId, finalExpiry);

        // 3. 한 번의 Retry와 한 세트의 Event만 Commit됐는지 최종 DB 상태로 검증한다.
        assertThat(results).filteredOn(RecoveryResult::recovered).hasSize(1);
        assertThat(results).filteredOn(result -> !result.recovered()).hasSize(1);
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", jobId))
            .isEqualTo("PENDING");
        assertThat(queryInteger("SELECT retry_count FROM embedding_jobs WHERE id = ?", jobId)).isOne();
        assertThat(queryDateTime("SELECT next_retry_at FROM embedding_jobs WHERE id = ?", jobId))
            .isAfter(finalExpiry);
        assertThat(eventCount(jobId, "LEASE_EXPIRED")).isOne();
        assertThat(eventCount(jobId, "RETRY")).isOne();
    }

    private WorkerJobPollingScheduler newPoller(
        Long workerId,
        ThreadPoolExecutor jobExecutor,
        WorkerIndexingPipeline pipeline,
        int capacity
    ) {
        return newPoller(workerId, jobExecutor, pipeline, new WorkerExecutionSlotPool(capacity));
    }

    private WorkerJobPollingScheduler newPoller(
        Long workerId,
        ThreadPoolExecutor jobExecutor,
        WorkerIndexingPipeline pipeline,
        WorkerExecutionSlotPool slotPool
    ) {
        WorkerLifecycleManager lifecycleManager = mock(WorkerLifecycleManager.class);
        given(lifecycleManager.getWorkerId()).willReturn(Optional.of(workerId));
        return new WorkerJobPollingScheduler(
            lifecycleManager,
            claimService,
            slotPool,
            jobExecutor,
            pipeline
        );
    }

    private ThreadPoolExecutor newJobExecutor(int capacity) {
        return new ThreadPoolExecutor(
            capacity,
            capacity,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>()
        );
    }

    private void runPollersConcurrently(List<WorkerJobPollingScheduler> pollers) throws Exception {
        CyclicBarrier startBarrier = new CyclicBarrier(pollers.size());
        ExecutorService executor = Executors.newFixedThreadPool(pollers.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (WorkerJobPollingScheduler poller : pollers) {
                futures.add(executor.submit(() -> {
                    startBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    poller.poll();
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<RecoveryResult> recoverConcurrently(
        Long jobId,
        LocalDateTime recoveredAt
    ) throws Exception {
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<RecoveryResult>> futures = List.of(
                executor.submit(() -> recoverAfterBarrier(jobId, recoveredAt, startBarrier)),
                executor.submit(() -> recoverAfterBarrier(jobId, recoveredAt, startBarrier))
            );
            return List.of(
                futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private RecoveryResult recoverAfterBarrier(
        Long jobId,
        LocalDateTime recoveredAt,
        CyclicBarrier startBarrier
    ) throws Exception {
        startBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return recoveryService.recover(jobId, recoveredAt);
    }

    private LocalDateTime awaitRenewedExpiry(
        Long jobId,
        LocalDateTime originalExpiry
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            LocalDateTime currentExpiry = queryDateTime(
                "SELECT lock_expires_at FROM embedding_jobs WHERE id = ?",
                jobId
            );
            if (currentExpiry.isAfter(originalExpiry)) {
                return currentExpiry;
            }
            Thread.sleep(20L);
        }
        throw new IllegalStateException("Lease 갱신이 제한 시간 안에 DB에 반영되지 않았습니다.");
    }

    private void awaitAvailableSlots(
        WorkerExecutionSlotPool slotPool,
        int expectedSlots
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (slotPool.getAvailableSlots() == expectedSlots) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new IllegalStateException("Worker 실행 슬롯이 제한 시간 안에 반환되지 않았습니다.");
    }

    private void shutdownExecutor(ThreadPoolExecutor executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private void shutdownLeaseManager(
        WorkerLeaseRenewalManager renewalManager,
        ScheduledThreadPoolExecutor leaseScheduler
    ) throws InterruptedException {
        renewalManager.stopAll();
        leaseScheduler.shutdownNow();
        assertThat(leaseScheduler.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private TestContext insertContext(String scenario) {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Worker Orchestration User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "worker-orchestration-" + suffix + "@example.com");
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility,
                created_at, updated_at
            )
            VALUES (?, ?, 'TXT', 'UPLOAD', 'UPLOADED', 'PRIVATE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId, "Worker Orchestration " + scenario + " " + suffix);
        Long versionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status,
                created_by, created_at, updated_at
            )
            VALUES (?, 1, ?, 'text/plain', 'PARSING', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, "Worker Orchestration " + scenario, userId);
        jdbcTemplate.update(
            "UPDATE documents SET current_version_id = ? WHERE id = ?",
            versionId,
            documentId
        );
        Long embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM embedding_models
            WHERE is_active = TRUE AND is_searchable = TRUE
            """, Long.class);
        return new TestContext(versionId, embeddingModelId);
    }

    private List<Long> insertPendingJobs(TestContext context, int count) {
        List<Long> jobIds = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            jobIds.add(jdbcTemplate.queryForObject("""
                INSERT INTO embedding_jobs (
                    document_version_id, embedding_model_id, status, priority,
                    retry_count, max_retry_count, created_at, updated_at
                )
                VALUES (?, ?, 'PENDING', 0, 0, 3,
                        CURRENT_TIMESTAMP + ? * INTERVAL '1 microsecond', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, context.versionId(), context.embeddingModelId(), index));
        }
        return jobIds;
    }

    private Long insertActiveWorker(String workerName) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO worker_nodes (
                worker_name, instance_id, host_name, ip_address, status,
                last_heartbeat_at, started_at, created_at, updated_at
            )
            VALUES (?, ?, 'localhost', '127.0.0.1', 'ACTIVE', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, workerName, UUID.randomUUID().toString());
    }

    private int countJobs(String status, Long workerId) {
        if (workerId == null) {
            return queryInteger("SELECT COUNT(*) FROM embedding_jobs WHERE status = ?", status);
        }
        return queryInteger(
            "SELECT COUNT(*) FROM embedding_jobs WHERE status = ? AND locked_by_worker_id = ?",
            status,
            workerId
        );
    }

    private int countEvents(String eventType) {
        return queryInteger("SELECT COUNT(*) FROM indexing_events WHERE event_type = ?", eventType);
    }

    private int eventCount(Long jobId, String eventType) {
        return queryInteger(
            "SELECT COUNT(*) FROM indexing_events WHERE embedding_job_id = ? AND event_type = ?",
            jobId,
            eventType
        );
    }

    private int queryInteger(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private Long queryLong(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private String queryString(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }

    private LocalDateTime queryDateTime(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class, arguments);
    }

    /**
     * 한 테스트 문서와 Version, 활성 Embedding Model의 식별자를 전달하는 DB 준비 결과다.
     */
    private record TestContext(Long versionId, Long embeddingModelId) {
    }
}
