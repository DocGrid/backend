package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;

@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Embedding Job Claim DB 동시성 통합 테스트")
class EmbeddingJobClaimIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_embedding_job_claim_test";
    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EmbeddingJobRepository embeddingJobRepository;

    @Autowired
    private EmbeddingJobClaimService embeddingJobClaimService;

    private ExecutorService executorService;
    private final AtomicInteger threadSequence = new AtomicInteger();

    @DynamicPropertySource
    static void useIsolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
    }

    @BeforeAll
    void createExecutor() {
        executorService = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("job-claim-worker-" + threadSequence.incrementAndGet());
            return thread;
        });
    }

    @BeforeEach
    void resetClaimState() {
        jdbcTemplate.update("DELETE FROM indexing_events");
        jdbcTemplate.update("DELETE FROM embedding_job_attempts");
        jdbcTemplate.update("DELETE FROM embedding_jobs");
        jdbcTemplate.update("DELETE FROM worker_nodes");
    }

    @AfterAll
    void cleanUpSchemaAndExecutor() throws InterruptedException {
        executorService.shutdownNow();
        assertThat(executorService.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("PENDING Job을 priority 내림차순, 생성 시각 오름차순으로 선택한다")
    void findNextPendingForUpdate_ordersClaimCandidate() {
        Long documentVersionId = insertDocumentVersion();
        Long lowPriorityJobId = insertJob(documentVersionId, "PENDING", 1, "2026-07-22 10:00:00");
        Long oldHighPriorityJobId = insertJob(documentVersionId, "PENDING", 10, "2026-07-22 09:00:00");
        insertJob(documentVersionId, "PENDING", 10, "2026-07-22 11:00:00");
        insertJob(documentVersionId, "PROCESSING", 100, "2026-07-22 08:00:00");

        Long selectedJobId = inNewTransaction(() ->
            embeddingJobRepository.findNextPendingForUpdate().orElseThrow().getId()
        );

        assertThat(selectedJobId).isEqualTo(oldHighPriorityJobId);
        assertThat(selectedJobId).isNotEqualTo(lowPriorityJobId);
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 PENDING Job은 기다리지 않고 다음 Job을 선택한다")
    void findNextPendingForUpdate_skipsLockedRow() throws Exception {
        Long documentVersionId = insertDocumentVersion();
        Long firstJobId = insertJob(documentVersionId, "PENDING", 10, "2026-07-22 09:00:00");
        Long secondJobId = insertJob(documentVersionId, "PENDING", 1, "2026-07-22 10:00:00");
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        Future<Long> lockHolder = executorService.submit(() -> inNewTransaction(() -> {
            Long selectedId = embeddingJobRepository.findNextPendingForUpdate().orElseThrow().getId();
            rowLocked.countDown();
            awaitLatch(releaseLock);
            return selectedId;
        }));

        assertThat(rowLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Future<Long> skipLockedReader = executorService.submit(() -> inNewTransaction(() ->
            embeddingJobRepository.findNextPendingForUpdate().orElseThrow().getId()
        ));

        try {
            assertThat(skipLockedReader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(secondJobId);
        } finally {
            releaseLock.countDown();
        }
        assertThat(lockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(firstJobId);
    }

    @Test
    @DisplayName("두 Worker가 동시에 하나의 Job을 Claim해도 한 Worker만 소유권을 얻는다")
    void claim_allowsExactlyOneConcurrentOwner() throws Exception {
        Long documentVersionId = insertDocumentVersion();
        Long jobId = insertJob(documentVersionId, "PENDING", 10, "2026-07-22 09:00:00");
        Long firstWorkerId = insertActiveWorker("worker-a");
        Long secondWorkerId = insertActiveWorker("worker-b");
        CyclicBarrier startBarrier = new CyclicBarrier(2);

        List<Future<Optional<ClaimedEmbeddingJobResponse>>> attempts = List.of(
            executorService.submit(() -> claimAfterBarrier(firstWorkerId, startBarrier)),
            executorService.submit(() -> claimAfterBarrier(secondWorkerId, startBarrier))
        );

        List<Optional<ClaimedEmbeddingJobResponse>> results = List.of(
            attempts.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            attempts.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        );

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(results).filteredOn(Optional::isEmpty).hasSize(1);

        ClaimedEmbeddingJobResponse claimedJob = results.stream()
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow();
        assertThat(claimedJob.jobId()).isEqualTo(jobId);
        assertThat(claimedJob.workerId()).isIn(firstWorkerId, secondWorkerId);
        assertThatCodeIsUuid(claimedJob.claimToken());
        assertThat(claimedJob.lockExpiresAt()).isAfter(claimedJob.lockedAt());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM embedding_jobs WHERE id = ?",
            String.class,
            jobId
        )).isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked_by_worker_id FROM embedding_jobs WHERE id = ?",
            Long.class,
            jobId
        )).isEqualTo(claimedJob.workerId());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT claim_token FROM embedding_jobs WHERE id = ?",
            String.class,
            jobId
        )).isEqualTo(claimedJob.claimToken());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM indexing_events WHERE embedding_job_id = ? AND event_type = 'LOCKED'",
            Integer.class,
            jobId
        )).isEqualTo(1);
    }

    @Test
    @DisplayName("V33 Migration이 claim_token 컬럼을 VARCHAR(36)으로 생성한다")
    void migration_createsClaimTokenColumn() {
        Integer length = jdbcTemplate.queryForObject("""
            SELECT character_maximum_length
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'embedding_jobs'
              AND column_name = 'claim_token'
            """, Integer.class);

        assertThat(length).isEqualTo(36);
    }

    private Optional<ClaimedEmbeddingJobResponse> claimAfterBarrier(Long workerId, CyclicBarrier barrier) {
        awaitBarrier(barrier);
        return embeddingJobClaimService.claim(workerId);
    }

    private void assertThatCodeIsUuid(String value) {
        assertThat(value).isNotBlank();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }

    private Long insertDocumentVersion() {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Claim Test User', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "claim-" + suffix + "@example.com");
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
            VALUES (?, 'Claim Test Document', 'TXT', 'UPLOAD', 'UPLOADED', 'PRIVATE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId);
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
            VALUES (?, 1, 'Claim Test Version', 'UPLOADED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, userId);
    }

    private Long insertJob(Long documentVersionId, String status, int priority, String createdAt) {
        Long embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM embedding_models
            WHERE is_active = TRUE
              AND is_searchable = TRUE
            """, Long.class);
        return jdbcTemplate.queryForObject("""
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
            VALUES (?, ?, ?, ?, 0, 3, CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP))
            RETURNING id
            """, Long.class, documentVersionId, embeddingModelId, status, priority, createdAt, createdAt);
    }

    private Long insertActiveWorker(String workerName) {
        return jdbcTemplate.queryForObject("""
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
            VALUES (?, ?, 'localhost', '127.0.0.1', 'ACTIVE', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, workerName, UUID.randomUUID().toString());
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate.execute(status -> work.get());
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 Lock 해제가 제한 시간 안에 완료되지 않았습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 Lock 대기 중 Thread가 중단되었습니다.", exception);
        }
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 Barrier 대기 중 Thread가 중단되었습니다.", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException("동시성 테스트 Barrier가 제한 시간 안에 완료되지 않았습니다.", exception);
        }
    }
}
