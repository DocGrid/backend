package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.config.EmbeddingBatchProperties;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerExecutionLifecycleManager;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerJobPollingScheduler;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerLifecycleManager;
import com.opensource.docgrid.e2e.LocalE2eApiClient.UploadedDocument;
import com.opensource.docgrid.e2e.LocalE2eApiClient.UploadedVersion;
import com.opensource.docgrid.e2e.LocalE2eDocumentFactory.DocumentPayload;

import io.minio.MinioClient;

/**
 * 제공된 실제 PDF 3건을 한 문서의 v1→v2→v3으로 처리해 인덱싱과 검색 전환을 계측한다.
 *
 * <p>외부 원문은 결과에 저장하지 않고 파일 Hash·크기와 Job·Attempt·Chunk 수치만 기록한다.
 * Test 전용 Schema와 Bucket을 사용하므로 기존 로컬 문서나 Object를 변경하지 않는다.
 */
@Tag("real-pdf-version-e2e")
@ActiveProfiles({"test", "minio-integration"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("실제 PDF v1→v2→v3 인덱싱 E2E")
class RealPdfVersionIndexingE2ETest {

    private static final String EXECUTION_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String TEST_SCHEMA = "docgrid_real_pdf_version_"
        + EXECUTION_ID.substring(0, 24);
    private static final String TEST_BUCKET = "docgrid-real-pdf-version-" + EXECUTION_ID;
    private static final Duration PIPELINE_TIMEOUT = Duration.ofMinutes(4);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MinioClient minioClient;
    @Autowired private EmbeddingClient embeddingClient;
    @Autowired private VectorSearchQueryService vectorSearchQueryService;
    @Autowired private EmbeddingBatchProperties batchProperties;
    @Autowired private WorkerLifecycleManager workerLifecycleManager;
    @Autowired private WorkerJobPollingScheduler pollingScheduler;
    @Autowired private WorkerExecutionLifecycleManager executionLifecycleManager;
    @Value("${embedding.document.read-timeout}") private Duration documentReadTimeout;

    private LocalE2eApiClient apiClient;
    private LocalE2eMinioBucket minioBucket;

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-real-pdf-version-e2e-secret-key-2026");
        registry.add("storage.bucket", () -> TEST_BUCKET);
        registry.add("indexing.worker.enabled", () -> "true");
        registry.add("indexing.worker.name", () -> "real-pdf-version-e2e-worker");
        registry.add("indexing.worker.polling-interval", () -> "100ms");
        registry.add("indexing.worker.heartbeat-interval", () -> "1s");
        registry.add("indexing.worker.dead-threshold", () -> "2m");
        registry.add("indexing.worker.max-concurrency", () -> "1");
        registry.add("indexing.worker.lease-duration", () -> "1m");
        registry.add("indexing.worker.lease-renewal-interval", () -> "5s");
        registry.add("indexing.worker.lease-recovery-interval", () -> "10m");
        registry.add("indexing.worker.shutdown-grace-period", () -> "15s");
    }

    @BeforeAll
    void setUpInfrastructure() throws Exception {
        apiClient = new LocalE2eApiClient(restTemplate);
        minioBucket = new LocalE2eMinioBucket(minioClient, TEST_BUCKET);
        minioBucket.create();
        awaitCondition(
            "Worker가 Application Ready 뒤 등록되지 않았습니다.",
            () -> workerLifecycleManager.getWorkerId().isPresent()
        );
    }

    @AfterAll
    void cleanUpInfrastructure() throws Exception {
        // 1. Worker Thread를 먼저 닫아 Schema 삭제 뒤 재접근하지 않게 한다.
        pollingScheduler.stopPolling();
        executionLifecycleManager.shutdown();
        workerLifecycleManager.stopWorker();

        // 2. 이 Test가 만든 Bucket과 Schema만 제거해 기존 로컬 Data를 보존한다.
        minioBucket.close();
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @Timeout(600)
    @DisplayName("세 PDF의 모든 Job이 성공하고 검색 가능 current 버전이 v3까지 전환된다")
    void indexesThreeRealPdfVersionsAndTransitionsSearchableCurrent() throws Exception {
        List<Path> pdfPaths = realPdfPaths();
        List<DocumentPayload> payloads = new ArrayList<>();
        for (int index = 0; index < pdfPaths.size(); index++) {
            Path path = pdfPaths.get(index);
            payloads.add(new DocumentPayload(
                path.getFileName().toString(),
                MediaType.APPLICATION_PDF,
                "실제 PDF 버전 E2E " + EXECUTION_ID,
                Files.readAllBytes(path)
            ));
        }

        String accessToken = apiClient.loginAdmin();
        List<VersionMeasurement> measurements = new ArrayList<>();
        long pipelineStartedAt = System.nanoTime();

        // 1. 최초 PDF를 새 문서로 접수하고 v1이 current·searchable이 될 때까지 측정한다.
        long versionStartedAt = System.nanoTime();
        UploadedDocument v1 = apiClient.upload(accessToken, payloads.get(0));
        measurements.add(awaitAndMeasure(
            1,
            pdfPaths.get(0),
            v1.documentId(),
            v1.documentVersionId(),
            v1.embeddingJobId(),
            versionStartedAt
        ));

        // 2. v2 접수 시 v1이 계속 current인지, 성공 후 v2로 전환되는지 검증한다.
        versionStartedAt = System.nanoTime();
        UploadedVersion v2 = apiClient.uploadVersion(accessToken, v1.documentId(), payloads.get(1));
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.currentVersionIdAtUpload()).isEqualTo(v1.documentVersionId());
        measurements.add(awaitAndMeasure(
            2,
            pdfPaths.get(1),
            v2.documentId(),
            v2.documentVersionId(),
            v2.embeddingJobId(),
            versionStartedAt
        ));

        // 3. 동일한 전환 계약을 v3에서 반복하고 최종 검색 결과가 v3만 참조하게 한다.
        versionStartedAt = System.nanoTime();
        UploadedVersion v3 = apiClient.uploadVersion(accessToken, v1.documentId(), payloads.get(2));
        assertThat(v3.versionNo()).isEqualTo(3);
        assertThat(v3.currentVersionIdAtUpload()).isEqualTo(v2.documentVersionId());
        measurements.add(awaitAndMeasure(
            3,
            pdfPaths.get(2),
            v3.documentId(),
            v3.documentVersionId(),
            v3.embeddingJobId(),
            versionStartedAt
        ));

        // 4. 로컬 원문은 제외하고 재현 설정과 정량 완료 지표만 JSON으로 보존한다.
        double elapsedSeconds = Duration.ofNanos(System.nanoTime() - pipelineStartedAt).toNanos()
            / 1_000_000_000.0;
        int totalChunks = measurements.stream().mapToInt(VersionMeasurement::chunkCount).sum();
        boolean latestVersionSearchable = measurements.get(measurements.size() - 1)
            .searchableCurrent();
        RealPdfVersionReport report = new RealPdfVersionReport(
            1,
            Instant.now().toString(),
            batchProperties.getBatchSize(),
            batchProperties.getMaxCodePoints(),
            batchProperties.getMaxEstimatedTokens(),
            documentReadTimeout.toSeconds(),
            measurements.size(),
            measurements.stream().filter(VersionMeasurement::jobSucceeded).count()
                / (double) measurements.size(),
            measurements.stream().mapToInt(VersionMeasurement::retryCount).sum(),
            elapsedSeconds * 1_000.0,
            totalChunks / elapsedSeconds,
            latestVersionSearchable,
            List.copyOf(measurements)
        );
        assertThat(report.jobSuccessRate()).isEqualTo(1.0);
        assertThat(report.totalRetryCount()).isZero();
        assertThat(report.latestVersionSearchable()).isTrue();
        writeReport(report);
    }

    private VersionMeasurement awaitAndMeasure(
        int versionNo,
        Path sourcePath,
        Long documentId,
        Long versionId,
        Long jobId,
        long startedAt
    ) throws Exception {
        awaitCondition(
            "Job이 INDEXED로 완료되지 않았습니다. jobId=" + jobId,
            () -> "INDEXED".equals(queryString(
                "SELECT status FROM embedding_jobs WHERE id = ?",
                jobId
            ))
        );

        Map<String, Object> job = jdbcTemplate.queryForMap(
            "SELECT status, retry_count, "
                + "EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000 AS duration_ms "
                + "FROM embedding_jobs WHERE id = ?",
            jobId
        );
        int attemptCount = count(
            "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ?",
            jobId
        );
        int successfulAttempts = count(
            "SELECT COUNT(*) FROM embedding_job_attempts "
                + "WHERE embedding_job_id = ? AND status = 'SUCCESS'",
            jobId
        );
        int chunkCount = count(
            "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ?",
            versionId
        );
        int embeddingCount = count(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ?",
            versionId
        );

        boolean jobSucceeded = "INDEXED".equals(job.get("status").toString());
        assertThat(jobSucceeded).isTrue();
        assertThat(((Number) job.get("retry_count")).intValue()).isZero();
        assertThat(attemptCount).isOne();
        assertThat(successfulAttempts).isOne();
        assertThat(queryLong("SELECT current_version_id FROM documents WHERE id = ?", documentId))
            .isEqualTo(versionId);
        assertThat(chunkCount).isPositive();
        assertThat(embeddingCount).isEqualTo(chunkCount);
        assertThat(count(
            "SELECT COUNT(*) FROM embeddings e "
                + "JOIN document_versions dv ON dv.id = e.document_version_id "
                + "WHERE dv.document_id = ? AND e.status = 'ACTIVE' AND dv.id <> ?",
            documentId,
            versionId
        )).isZero();

        boolean searchable = searchUsesCurrentVersion(documentId, versionId);
        assertThat(searchable).isTrue();
        return new VersionMeasurement(
            versionNo,
            sourcePath.getFileName().toString(),
            Files.size(sourcePath),
            sha256(Files.readAllBytes(sourcePath)),
            versionId,
            jobId,
            jobSucceeded,
            ((Number) job.get("retry_count")).intValue(),
            attemptCount,
            ((Number) job.get("duration_ms")).doubleValue(),
            Duration.ofNanos(System.nanoTime() - startedAt).toNanos() / 1_000_000.0,
            chunkCount,
            embeddingCount,
            searchable
        );
    }

    private boolean searchUsesCurrentVersion(Long documentId, Long versionId) {
        String sourceChunk = jdbcTemplate.queryForObject(
            "SELECT chunk_text FROM document_chunks WHERE document_version_id = ? "
                + "ORDER BY chunk_index LIMIT 1",
            String.class,
            versionId
        );
        Long modelId = queryLong(
            "SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE"
        );
        float[] queryVector = embeddingClient.embed(sourceChunk);
        List<VectorSearchCandidate> candidates = vectorSearchQueryService.search(
            queryVector,
            modelId,
            List.of(documentId),
            10
        );
        return !candidates.isEmpty() && candidates.stream().allMatch(candidate ->
            versionId.equals(queryLong(
                "SELECT document_version_id FROM embeddings WHERE id = ?",
                candidate.embeddingId()
            ))
        );
    }

    private List<Path> realPdfPaths() {
        String rawPaths = System.getenv("REAL_PDF_PATHS");
        assertThat(rawPaths)
            .as("실제 PDF E2E 실행 전에 REAL_PDF_PATHS에 세 PDF 경로를 설정해야 합니다.")
            .isNotBlank();
        List<Path> paths = java.util.Arrays.stream(rawPaths.split(java.util.regex.Pattern.quote(
                File.pathSeparator
            )))
            .filter(path -> !path.isBlank())
            .map(Path::of)
            .map(path -> path.toAbsolutePath().normalize())
            .toList();
        assertThat(paths).hasSize(3);
        assertThat(paths).allMatch(Files::isRegularFile);
        return paths;
    }

    private void awaitCondition(String failureMessage, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + PIPELINE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(failureMessage);
    }

    private int count(String sql, Object... args) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private Long queryLong(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private void writeReport(RealPdfVersionReport report) throws Exception {
        Path outputPath = Path.of(System.getProperty(
            "real.pdf.version.e2e.output",
            "build/reports/real-pdf-embedding/real-pdf-version-e2e.json"
        )).toAbsolutePath().normalize();
        Files.createDirectories(outputPath.getParent());
        new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .writeValue(outputPath.toFile(), report);
    }

    /** 실제 PDF 세 버전의 완료율·재시도·처리량과 설정 지문을 보존한다. */
    private record RealPdfVersionReport(
        int schemaVersion,
        String generatedAt,
        int batchSize,
        int maxBatchCodePoints,
        int maxBatchEstimatedTokens,
        long documentReadTimeoutSeconds,
        int versionCount,
        double jobSuccessRate,
        int totalRetryCount,
        double totalPipelineMilliseconds,
        double throughputChunksPerSecond,
        boolean latestVersionSearchable,
        List<VersionMeasurement> versions
    ) {
    }

    /** 한 문서 버전의 원본 지문과 Job·Attempt·Vector 완료 지표를 보존한다. */
    private record VersionMeasurement(
        int versionNo,
        String sourceName,
        long sourceSizeBytes,
        String sourceSha256,
        Long documentVersionId,
        Long embeddingJobId,
        boolean jobSucceeded,
        int retryCount,
        int attemptCount,
        double jobDurationMilliseconds,
        double pipelineDurationMilliseconds,
        int chunkCount,
        int embeddingCount,
        boolean searchableCurrent
    ) {
    }
}
