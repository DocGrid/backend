package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseRecoveryService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseRecoveryService.RecoveryResult;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingJobRecoveryQueryService;

/**
 * 실제 PostgreSQL에서 만료 Lease 후보 조회와 Job별 복구 Transaction의 동시성 경계를 검증한다.
 *
 * <p>격리 Schema에 PROCESSING Job을 직접 구성하고 실제 {@code FOR UPDATE SKIP LOCKED}를 사용해 후보
 * 순서, 단일 회수, 선택적 Attempt 종료, Retry와 최종 실패의 원자적 상태를 확인한다.
 */
@Tag("integration")
@Tag("claim-concurrency")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Embedding Job Lease 복구 PostgreSQL 통합 테스트")
class EmbeddingJobLeaseRecoveryIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_lease_recovery_integration_test";
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final LocalDateTime RECOVERED_AT = LocalDateTime.of(2026, 8, 3, 15, 0);
    private static final int CONCURRENT_REQUESTS = 2;
    private static final long TIMEOUT_SECONDS = 10;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EmbeddingJobRecoveryQueryService recoveryQueryService;
    @Autowired private EmbeddingJobLeaseRecoveryService recoveryService;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-lease-recovery-integration-test-secret-key-2026");
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
    @DisplayName("만료된 PROCESSING Job만 오래된 Lease 순서와 Batch 크기로 조회한다")
    void findExpiredJobIds_returnsOrderedBoundedSnapshot() {
        RecoveryContext context = insertRecoveryContext();
        Long oldestJobId = insertProcessingJob(
            context,
            RECOVERED_AT.minusMinutes(3),
            3,
            false
        );
        Long secondJobId = insertProcessingJob(
            context,
            RECOVERED_AT.minusMinutes(2),
            3,
            false
        );
        Long boundaryJobId = insertProcessingJob(context, RECOVERED_AT, 3, false);
        insertProcessingJob(context, RECOVERED_AT.plusSeconds(1), 3, false);
        insertPendingJob(context, RECOVERED_AT.minusMinutes(10));
        insertProcessingJobWithoutExpiry(context);

        List<Long> firstBatch = recoveryQueryService.findExpiredJobIds(RECOVERED_AT, 2);
        List<Long> allCandidates = recoveryQueryService.findExpiredJobIds(RECOVERED_AT, 10);

        assertThat(firstBatch).containsExactly(oldestJobId, secondJobId);
        assertThat(allCandidates).containsExactly(oldestJobId, secondJobId, boundaryJobId);
    }

    @Test
    @DisplayName("같은 만료 Job을 동시에 복구해도 정확히 한 Transaction만 Retry를 반영한다")
    void recoverConcurrently_transitionsExactlyOnce() throws Exception {
        RecoveryContext context = insertRecoveryContext();
        Long jobId = insertProcessingJob(
            context,
            RECOVERED_AT.minusSeconds(1),
            3,
            true
        );
        CyclicBarrier startBarrier = new CyclicBarrier(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);

        List<RecoveryResult> results;
        try {
            List<Future<RecoveryResult>> futures = List.of(
                executor.submit(() -> recoverAfterBarrier(jobId, startBarrier)),
                executor.submit(() -> recoverAfterBarrier(jobId, startBarrier))
            );
            results = List.of(
                futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).filteredOn(RecoveryResult::recovered).hasSize(1);
        assertThat(results).filteredOn(result -> !result.recovered()).hasSize(1);
        assertRetryState(jobId, true);
        assertThat(eventCount(jobId, "LEASE_EXPIRED")).isOne();
        assertThat(eventCount(jobId, "PARSE_FAILED")).isOne();
        assertThat(eventCount(jobId, "RETRY")).isOne();
        assertThat(queryString(
            "SELECT metadata_json FROM indexing_events WHERE embedding_job_id = ? AND event_type = 'LEASE_EXPIRED'",
            jobId
        )).doesNotContain(CLAIM_TOKEN);
    }

    @Test
    @DisplayName("Attempt 시작 전 만료된 Job은 실행 이력을 만들지 않고 Retry한다")
    void recover_withoutAttempt_doesNotCreateSyntheticAttempt() {
        RecoveryContext context = insertRecoveryContext();
        Long jobId = insertProcessingJob(
            context,
            RECOVERED_AT.minusSeconds(1),
            3,
            false
        );

        RecoveryResult result = recoveryService.recover(jobId, RECOVERED_AT);

        assertThat(result.recovered()).isTrue();
        assertRetryState(jobId, false);
        assertThat(queryInteger(
            "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ?",
            jobId
        )).isZero();
        assertThat(eventCount(jobId, "LEASE_EXPIRED")).isOne();
        assertThat(eventCount(jobId, "PARSE_FAILED")).isOne();
        assertThat(eventCount(jobId, "RETRY")).isOne();
    }

    @Test
    @DisplayName("Retry 횟수를 소진한 만료 Job은 Job과 현재 문서를 최종 실패로 종결한다")
    void recover_withoutRemainingRetries_transitionsToTerminalFailure() {
        RecoveryContext context = insertRecoveryContext();
        Long jobId = insertProcessingJob(
            context,
            RECOVERED_AT.minusSeconds(1),
            0,
            true
        );

        RecoveryResult result = recoveryService.recover(jobId, RECOVERED_AT);

        assertThat(result.recovered()).isTrue();
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", jobId))
            .isEqualTo("FAILED");
        assertThat(queryString("SELECT status FROM document_versions WHERE id = ?", context.versionId()))
            .isEqualTo("FAILED");
        assertThat(queryString("SELECT status FROM documents WHERE id = ?", context.documentId()))
            .isEqualTo("FAILED");
        assertThat(queryString(
            "SELECT status FROM embedding_job_attempts WHERE embedding_job_id = ?",
            jobId
        )).isEqualTo("FAILED");
        assertThat(eventCount(jobId, "LEASE_EXPIRED")).isOne();
        assertThat(eventCount(jobId, "PARSE_FAILED")).isOne();
        assertThat(eventCount(jobId, "FAILED")).isOne();
        assertThat(eventCount(jobId, "RETRY")).isZero();
    }

    private RecoveryResult recoverAfterBarrier(Long jobId, CyclicBarrier startBarrier) throws Exception {
        startBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return recoveryService.recover(jobId, RECOVERED_AT);
    }

    private RecoveryContext insertRecoveryContext() {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Lease Recovery User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "lease-recovery-" + suffix + "@example.com");
        Long workerId = jdbcTemplate.queryForObject("""
            INSERT INTO worker_nodes (
                worker_name, instance_id, status, last_heartbeat_at, started_at,
                created_at, updated_at
            )
            VALUES ('lease-recovery-worker', ?, 'ACTIVE', ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, suffix, RECOVERED_AT.minusMinutes(10), RECOVERED_AT.minusHours(1));
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility,
                created_at, updated_at
            )
            VALUES (?, 'Lease Recovery Document', 'TXT', 'UPLOAD', 'UPLOADED', 'PRIVATE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId);
        Long versionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status,
                created_by, created_at, updated_at
            )
            VALUES (?, 1, 'Lease Recovery Version', 'text/plain', 'PARSING', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, userId);
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
        return new RecoveryContext(workerId, documentId, versionId, embeddingModelId);
    }

    private Long insertProcessingJob(
        RecoveryContext context,
        LocalDateTime lockExpiresAt,
        int maxRetryCount,
        boolean withAttempt
    ) {
        String claimToken = withAttempt ? CLAIM_TOKEN : UUID.randomUUID().toString();
        Long jobId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, locked_by_worker_id, locked_at, lock_expires_at,
                claim_token, started_at, created_at, updated_at
            )
            VALUES (?, ?, 'PROCESSING', 0, 0, ?, ?, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """,
            Long.class,
            context.versionId(),
            context.embeddingModelId(),
            maxRetryCount,
            context.workerId(),
            RECOVERED_AT.minusMinutes(5),
            lockExpiresAt,
            claimToken,
            RECOVERED_AT.minusMinutes(5)
        );
        if (withAttempt) {
            jdbcTemplate.update("""
                INSERT INTO embedding_job_attempts (
                    embedding_job_id, worker_node_id, attempt_no, claim_token, status,
                    started_at, created_at, updated_at
                )
                VALUES (?, ?, 1, ?, 'STARTED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                jobId,
                context.workerId(),
                claimToken,
                RECOVERED_AT.minusMinutes(4)
            );
        }
        return jobId;
    }

    private void insertPendingJob(RecoveryContext context, LocalDateTime lockExpiresAt) {
        jdbcTemplate.update("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, lock_expires_at, created_at, updated_at
            )
            VALUES (?, ?, 'PENDING', 0, 0, 3, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, context.versionId(), context.embeddingModelId(), lockExpiresAt);
    }

    private void insertProcessingJobWithoutExpiry(RecoveryContext context) {
        jdbcTemplate.update("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, locked_by_worker_id, locked_at, claim_token, started_at,
                created_at, updated_at
            )
            VALUES (?, ?, 'PROCESSING', 0, 0, 3, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            context.versionId(),
            context.embeddingModelId(),
            context.workerId(),
            RECOVERED_AT.minusMinutes(5),
            UUID.randomUUID().toString(),
            RECOVERED_AT.minusMinutes(5)
        );
    }

    private void assertRetryState(Long jobId, boolean hasAttempt) {
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", jobId))
            .isEqualTo("PENDING");
        assertThat(queryInteger("SELECT retry_count FROM embedding_jobs WHERE id = ?", jobId))
            .isOne();
        assertThat(queryString("SELECT claim_token FROM embedding_jobs WHERE id = ?", jobId))
            .isNull();
        assertThat(queryDateTime("SELECT lock_expires_at FROM embedding_jobs WHERE id = ?", jobId))
            .isNull();
        if (hasAttempt) {
            assertThat(queryString(
                "SELECT status FROM embedding_job_attempts WHERE embedding_job_id = ?",
                jobId
            )).isEqualTo("FAILED");
        }
    }

    private int eventCount(Long jobId, String eventType) {
        return queryInteger("""
            SELECT COUNT(*)
            FROM indexing_events
            WHERE embedding_job_id = ? AND event_type = ?
            """, jobId, eventType);
    }

    private String queryString(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }

    private Integer queryInteger(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private LocalDateTime queryDateTime(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class, arguments);
    }

    /**
     * 한 테스트의 Worker, Document, Version과 Model 식별자를 묶는 DB Fixture Context.
     */
    private record RecoveryContext(
        Long workerId,
        Long documentId,
        Long versionId,
        Long embeddingModelId
    ) {
    }
}
