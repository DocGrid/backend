package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerExecutionLifecycleManager;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerJobPollingScheduler;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerLifecycleManager;
import com.opensource.docgrid.e2e.LocalE2eApiClient.UploadedDocument;
import com.opensource.docgrid.e2e.LocalE2eDocumentFactory.DocumentPayload;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.minio.MinioClient;

/**
 * 파일 저장소 Provider별 실제 HTTP 업로드와 자동 Worker 인덱싱 계약을 공통 검증한다.
 * Spring Context 설정과 격리 위치는 하위 Test가 제공하고 이 클래스는 제품 경계 시나리오만 소유한다.
 */
abstract class AbstractFileStorageWorkerE2ETest {

    private static final Duration PIPELINE_TIMEOUT = Duration.ofMinutes(4);
    private static final String MINIO_ENDPOINT = "http://127.0.0.1:9000";
    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin1234";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private WorkerLifecycleManager workerLifecycleManager;
    @Autowired private WorkerJobPollingScheduler pollingScheduler;
    @Autowired private WorkerExecutionLifecycleManager executionLifecycleManager;

    private LocalE2eApiClient apiClient;
    private LocalE2eMinioBucket remoteBucket;

    protected static void configureBaseEnvironment(
        DynamicPropertyRegistry registry,
        String schema,
        String bucket,
        StorageProvider provider,
        String workerName
    ) {
        registry.add("TEST_DB_SCHEMA", () -> schema);
        registry.add("jwt.secret", () -> "docgrid-storage-worker-e2e-secret-key-2026");
        registry.add("storage.type", () -> provider.name().toLowerCase());
        registry.add("storage.bucket", () -> bucket);
        registry.add("indexing.worker.enabled", () -> "true");
        registry.add("indexing.worker.name", () -> workerName);
        registry.add("indexing.worker.polling-interval", () -> "100ms");
        registry.add("indexing.worker.heartbeat-interval", () -> "1s");
        registry.add("indexing.worker.dead-threshold", () -> "2m");
        registry.add("indexing.worker.max-concurrency", () -> "1");
        registry.add("indexing.worker.lease-duration", () -> "30s");
        registry.add("indexing.worker.lease-renewal-interval", () -> "5s");
        registry.add("indexing.worker.lease-recovery-interval", () -> "10m");
        registry.add("indexing.worker.shutdown-grace-period", () -> "10s");
    }

    protected static void configureMinioConnection(DynamicPropertyRegistry registry) {
        registry.add("minio.endpoint", () -> MINIO_ENDPOINT);
        registry.add("minio.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("minio.secret-key", () -> MINIO_SECRET_KEY);
    }

    protected static void configureS3CompatibleConnection(DynamicPropertyRegistry registry) {
        registry.add("s3.region", () -> "ap-northeast-2");
        registry.add("s3.endpoint", () -> MINIO_ENDPOINT);
        registry.add("s3.path-style-access-enabled", () -> "true");
        registry.add("s3.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("s3.secret-key", () -> MINIO_SECRET_KEY);
    }

    @BeforeAll
    void setUpInfrastructure() throws Exception {
        apiClient = new LocalE2eApiClient(restTemplate);
        if (usesRemoteBucket()) {
            MinioClient adminClient = MinioClient.builder()
                .endpoint(MINIO_ENDPOINT)
                .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
                .build();
            remoteBucket = new LocalE2eMinioBucket(adminClient, bucket());
            remoteBucket.create();
        }
        awaitCondition(
            "Worker가 Application Ready 뒤 등록되지 않았습니다.",
            () -> workerLifecycleManager.getWorkerId().isPresent()
        );
    }

    @AfterAll
    void cleanUpInfrastructure() throws Exception {
        Exception cleanupFailure = null;

        // 1. Worker 종료 단계가 하나 실패해도 나머지 Thread와 등록 정보를 계속 정리한다.
        try {
            pollingScheduler.stopPolling();
        } catch (Exception exception) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
        }
        try {
            executionLifecycleManager.shutdown();
        } catch (Exception exception) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
        }
        try {
            workerLifecycleManager.stopWorker();
        } catch (Exception exception) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
        }

        // 2. 외부 Bucket 정리에 실패해도 Local Root와 DB Schema 정리를 독립적으로 시도한다.
        try {
            if (remoteBucket != null) {
                remoteBucket.close();
            }
        } catch (Exception exception) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
        }
        try {
            deleteLocalRoot();
        } catch (Exception exception) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
        }

        // 3. 마지막 Schema 제거도 항상 시도하고, 누적된 첫 실패에 나머지 원인을 보존한다.
        try {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema() + " CASCADE");
        } catch (Exception exception) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, exception);
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    @Test
    @Timeout(300)
    @DisplayName("실제 업로드 파일을 선택된 저장소에서 읽고 자동 Worker가 인덱싱을 완료한다")
    void upload_indexesAndReadsFileWithConfiguredStorage() throws Exception {
        String accessToken = apiClient.loginAdmin();
        DocumentPayload payload = LocalE2eDocumentFactory.pdf(
            provider().name().toLowerCase() + "-worker-e2e.pdf",
            provider().name() + " Storage Worker E2E",
            "DocGrid stores the original document through a provider-neutral file storage port.",
            "The indexing worker reads the same object and creates deterministic document embeddings."
        );

        // 1. 실제 인증과 Multipart HTTP를 통해 선택된 Adapter에 원본을 저장하고 Job을 접수한다.
        UploadedDocument upload = apiClient.upload(accessToken, payload);

        // 2. DB Snapshot의 Provider·Bucket·Object Key로 실제 저장된 Byte를 다시 읽는다.
        StoredFile storedFile = findStoredFile(upload.fileObjectId());
        assertThat(storedFile.storageProvider()).isEqualTo(provider());
        assertThat(storedFile.bucketName()).isEqualTo(bucket());
        assertThat(fileStorageService.read(storedFile)).isEqualTo(payload.content());

        // 3. 자동 Worker가 실제 Parser와 BGE-M3를 거쳐 Job·문서 상태와 Vector를 완료한다.
        awaitCondition(
            "Job이 INDEXED로 완료되지 않았습니다. " + jobSnapshot(upload.embeddingJobId()),
            () -> "INDEXED".equals(queryString(
                "SELECT status FROM embedding_jobs WHERE id = ?",
                upload.embeddingJobId()
            ))
        );
        assertIndexed(upload);

        // 4. 실제 Object 삭제 뒤 같은 Snapshot 조회가 파일 누락 오류로 분류되는지 확인한다.
        fileStorageService.delete(storedFile);
        assertThatThrownBy(() -> fileStorageService.read(storedFile))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FILE_OBJECT_NOT_FOUND));
    }

    protected abstract StorageProvider provider();

    protected abstract String schema();

    protected abstract String bucket();

    protected abstract boolean usesRemoteBucket();

    protected abstract Path localRoot();

    private StoredFile findStoredFile(Long fileObjectId) {
        return jdbcTemplate.queryForObject(
            "SELECT storage_provider, bucket_name, object_key FROM file_objects WHERE id = ?",
            (resultSet, rowNum) -> new StoredFile(
                StorageProvider.valueOf(resultSet.getString("storage_provider")),
                resultSet.getString("bucket_name"),
                resultSet.getString("object_key")
            ),
            fileObjectId
        );
    }

    private void assertIndexed(UploadedDocument upload) {
        assertThat(queryString("SELECT status FROM documents WHERE id = ?", upload.documentId()))
            .isEqualTo("INDEXED");
        assertThat(queryString(
            "SELECT status FROM document_versions WHERE id = ?",
            upload.documentVersionId()
        )).isEqualTo("INDEXED");
        int chunkCount = count(
            "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ?",
            upload.documentVersionId()
        );
        assertThat(chunkCount).isPositive();
        assertThat(count(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ?",
            upload.documentVersionId()
        )).isEqualTo(chunkCount);
        assertThat(count(
            "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ? AND status = 'SUCCESS'",
            upload.embeddingJobId()
        )).isOne();
    }

    private void deleteLocalRoot() throws Exception {
        Path root = localRoot();
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Exception appendCleanupFailure(Exception currentFailure, Exception nextFailure) {
        if (currentFailure == null) {
            return nextFailure;
        }
        currentFailure.addSuppressed(nextFailure);
        return currentFailure;
    }

    private void awaitCondition(String failureMessage, CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + PIPELINE_TIMEOUT.toNanos();
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

    /** 실제 외부·DB 조건을 제한 시간 안에서 반복 확인하는 E2E 함수다. */
    @FunctionalInterface
    private interface CheckedCondition {

        boolean evaluate() throws Exception;
    }
}
