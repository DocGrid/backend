package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.worker.lifecycle.WorkerExecutionLifecycleManager;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerJobPollingScheduler;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerLifecycleManager;
import com.opensource.docgrid.e2e.LocalE2eApiClient.UploadedDocument;
import com.opensource.docgrid.e2e.LocalE2eDocumentFactory.DocumentPayload;

import io.minio.MinioClient;

/**
 * 실제 PostgreSQL·MinIO에서 Embedding Provider 연결 실패의 Retry와 부분 저장 방지를 관통 검증한다.
 *
 * <p>성공 E2E와 별도 Context·Schema·Bucket을 사용하고, 연결 불가능한 Test Endpoint만 주입해 제품의
 * 실제 오류 분류와 실패 Transaction을 실행한다.
 */
@Tag("local-e2e")
@ActiveProfiles({"test", "minio-integration"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Embedding Provider 장애 로컬 전체 관통 E2E")
class LocalEmbeddingProviderOutageE2ETest {

    private static final String TEST_SCHEMA = "docgrid_local_embedding_outage_e2e";
    private static final String TEST_BUCKET = "docgrid-outage-e2e-"
        + UUID.randomUUID().toString().replace("-", "");
    private static final Duration FAILURE_TIMEOUT = Duration.ofMinutes(2);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MinioClient minioClient;
    @Autowired private WorkerLifecycleManager workerLifecycleManager;
    @Autowired private WorkerJobPollingScheduler pollingScheduler;
    @Autowired private WorkerExecutionLifecycleManager executionLifecycleManager;

    private LocalE2eApiClient apiClient;
    private LocalE2eMinioBucket minioBucket;

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-local-embedding-outage-e2e-secret-key-2026");
        registry.add("minio.bucket", () -> TEST_BUCKET);
        registry.add("embedding.server.base-url", () -> "http://127.0.0.1:65534");
        registry.add("indexing.worker.enabled", () -> "true");
        registry.add("indexing.worker.name", () -> "local-embedding-outage-e2e-worker");
        registry.add("indexing.worker.polling-interval", () -> "100ms");
        registry.add("indexing.worker.heartbeat-interval", () -> "1s");
        registry.add("indexing.worker.dead-threshold", () -> "2m");
        registry.add("indexing.worker.max-concurrency", () -> "1");
        registry.add("indexing.worker.lease-duration", () -> "30s");
        registry.add("indexing.worker.lease-renewal-interval", () -> "5s");
        registry.add("indexing.worker.lease-recovery-interval", () -> "10m");
        registry.add("indexing.worker.retry-initial-delay", () -> "10m");
        registry.add("indexing.worker.retry-max-delay", () -> "10m");
        registry.add("indexing.worker.shutdown-grace-period", () -> "10s");
    }

    @BeforeAll
    void setUpInfrastructure() throws Exception {
        apiClient = new LocalE2eApiClient(restTemplate);
        minioBucket = new LocalE2eMinioBucket(minioClient, TEST_BUCKET);
        minioBucket.create();
        awaitFailureState(
            "Worker가 Application Ready 뒤 등록되지 않았습니다.",
            () -> workerLifecycleManager.getWorkerId().isPresent()
        );
    }

    @AfterAll
    void cleanUpInfrastructure() throws Exception {
        // 1. 실패 Job의 다음 Claim을 막고 실행·Worker 생명주기를 안전한 순서로 닫는다.
        pollingScheduler.stopPolling();
        executionLifecycleManager.shutdown();
        workerLifecycleManager.stopWorker();

        // 2. 장애 Test가 만든 외부·DB 자원만 제거한다.
        minioBucket.close();
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @Timeout(180)
    @DisplayName("Provider 연결 실패는 Chunk를 유지하고 Vector 없이 지연 재시도로 수렴한다")
    void providerOutage_schedulesRetryWithoutPartialVectors() throws Exception {
        String accessToken = apiClient.loginAdmin();
        DocumentPayload payload = LocalE2eDocumentFactory.text(
            "provider-outage.txt",
            "Embedding Provider Outage",
            "Embedding provider outage must preserve parsed chunks without storing partial vectors."
        );

        // 1. 실제 HTTP와 MinIO로 문서를 접수해 자동 Worker가 Provider 실패까지 실행하게 한다.
        UploadedDocument upload = apiClient.upload(accessToken, payload);
        awaitFailureState(
            "Provider 실패가 지연 재시도로 수렴하지 않았습니다. " + jobSnapshot(upload.embeddingJobId()),
            () -> isRetryPending(upload.embeddingJobId())
        );

        // 2. Parsing 결과는 유지하되 Embedding은 하나도 저장되지 않은 원자성 경계를 확인한다.
        assertThat(count(
            "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ?",
            upload.documentVersionId()
        )).isPositive();
        assertThat(count(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ?",
            upload.documentVersionId()
        )).isZero();

        // 3. Attempt·오류 분류·Retry 예약과 Event가 한 번만 기록됐는지 확인한다.
        assertThat(count(
            "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ? AND status = 'FAILED'",
            upload.embeddingJobId()
        )).isOne();
        assertThat(queryString(
            "SELECT error_code FROM embedding_jobs WHERE id = ?",
            upload.embeddingJobId()
        )).isEqualTo("EMBEDDING_PROVIDER_UNAVAILABLE");
        assertThat(count(
            "SELECT COUNT(*) FROM indexing_events WHERE embedding_job_id = ? AND event_type = 'EMBEDDING_FAILED'",
            upload.embeddingJobId()
        )).isOne();
        assertThat(count(
            "SELECT COUNT(*) FROM indexing_events WHERE embedding_job_id = ? AND event_type = 'RETRY'",
            upload.embeddingJobId()
        )).isOne();
    }

    private boolean isRetryPending(Long jobId) {
        return count(
            "SELECT COUNT(*) FROM embedding_jobs WHERE id = ? AND status = 'PENDING' "
                + "AND retry_count = 1 AND next_retry_at IS NOT NULL",
            jobId
        ) == 1;
    }

    private void awaitFailureState(String failureMessage, CheckedCondition condition) throws InterruptedException {
        long deadline = System.nanoTime() + FAILURE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(failureMessage);
    }

    private String jobSnapshot(Long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT status || ',retry=' || retry_count || ',error=' || COALESCE(error_code, 'none') "
                + "FROM embedding_jobs WHERE id = ?",
            String.class,
            jobId
        );
    }

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private String queryString(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }

    /** Provider 실패 상태를 제한 시간 동안 확인하는 DB 조건을 표현한다. */
    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate();
    }
}
