package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.embedding.config.EmbeddingBatchProperties;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerExecutionLifecycleManager;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerJobPollingScheduler;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerLifecycleManager;
import com.opensource.docgrid.e2e.DocumentIndexingE2ELoadStatistics.DocumentFormat;
import com.opensource.docgrid.e2e.DocumentIndexingE2ELoadStatistics.DocumentMeasurement;
import com.opensource.docgrid.e2e.DocumentIndexingE2ELoadStatistics.FormatSummary;
import com.opensource.docgrid.e2e.LocalE2eApiClient.UploadedDocument;
import com.opensource.docgrid.e2e.LocalE2eDocumentFactory.DocumentPayload;
import com.opensource.docgrid.e2e.LocalE2eDocumentFactory.DocumentSection;

import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 실제 PDF·DOCX 50·100문서를 PostgreSQL·MinIO·BGE-M3 전체 인덱싱 Pipeline으로 부하 검증한다.
 *
 * <p>Text Layer PDF와 OOXML DOCX를 실제 Multipart HTTP로 동시에 접수하고 자동 Worker가 Queue를
 * 소진하게 한다. 전체와 형식별 처리량·지연을 기록하면서 페이지·Section Metadata, Attempt, Event,
 * Chunk와 1024차원 Vector의 완전성을 검증한다.
 */
@Slf4j
@Tag("document-indexing-e2e-load")
@ActiveProfiles({"test", "minio-integration"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PDF DOCX 전체 인덱싱 E2E 부하 Benchmark")
class DocumentIndexingE2ELoadBenchmark {

    private static final String EXECUTION_ID = UUID.randomUUID().toString().replace("-", "");
    // PostgreSQL 식별자 63자 제한 안에서 별도 실행이 Schema를 공유하지 않도록 격리한다.
    private static final String TEST_SCHEMA = "docgrid_document_e2e_load_"
        + EXECUTION_ID.substring(0, 24);
    private static final String TEST_BUCKET = "docgrid-document-e2e-load-" + EXECUTION_ID;
    private static final String EXPECTED_POSTGRES_VERSION_PREFIX = "17.";
    private static final String EXPECTED_PGVECTOR_VERSION = "0.8.1";
    private static final String EXPECTED_MODEL = "BAAI/bge-m3";
    private static final int EXPECTED_VECTOR_DIMENSION = 1024;
    private static final int SECTION_CHARACTER_COUNT = positiveIntegerProperty(
        "document.indexing.e2e.load.section-characters",
        1_600
    );
    private static final int WARM_UP_DOCUMENT_COUNT = positiveEvenIntegerProperty(
        "document.indexing.e2e.load.warm-up-documents",
        4
    );
    private static final List<Integer> DOCUMENT_COUNTS = positiveEvenIntegerListProperty(
        "document.indexing.e2e.load.document-counts",
        List.of(50, 100)
    );
    private static final int REPETITIONS = positiveIntegerProperty(
        "document.indexing.e2e.load.repetitions",
        2
    );
    private static final int UPLOADER_THREADS = positiveIntegerProperty(
        "document.indexing.e2e.load.uploader-threads",
        8
    );
    private static final long PROFILE_TIMEOUT_SECONDS = positiveLongProperty(
        "document.indexing.e2e.load.profile-timeout-seconds",
        1_200L
    );
    private static final long POLLING_SLEEP_MILLIS = positiveLongProperty(
        "document.indexing.e2e.load.status-polling-ms",
        100L
    );
    private static final Path OUTPUT_PATH = Path.of(System.getProperty(
        "document.indexing.e2e.load.output",
        "build/reports/document-indexing-e2e-load/document-indexing-e2e-load.json"
    ));

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MinioClient minioClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WorkerLifecycleManager workerLifecycleManager;
    @Autowired private WorkerJobPollingScheduler pollingScheduler;
    @Autowired private WorkerExecutionLifecycleManager executionLifecycleManager;
    @Autowired private WorkerExecutionSlotPool executionSlotPool;
    @Autowired private IndexingWorkerProperties workerProperties;
    @Autowired private EmbeddingBatchProperties embeddingBatchProperties;
    @Autowired @Qualifier("embeddingRestClient") private RestClient embeddingRestClient;

    private LocalE2eApiClient apiClient;
    private LocalE2eMinioBucket minioBucket;
    private String accessToken;

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-document-e2e-load-test-secret-key-2026");
        registry.add("minio.bucket", () -> TEST_BUCKET);
        registry.add("indexing.worker.enabled", () -> "true");
        registry.add("indexing.worker.name", () -> "document-indexing-e2e-load-benchmark");
        registry.add("indexing.worker.polling-interval", () -> "50ms");
        registry.add("indexing.worker.heartbeat-interval", () -> "1s");
        registry.add("indexing.worker.dead-threshold", () -> "2m");
        registry.add("indexing.worker.max-concurrency", () -> "2");
        registry.add("indexing.worker.lease-duration", () -> "2m");
        registry.add("indexing.worker.lease-renewal-interval", () -> "10s");
        registry.add("indexing.worker.lease-recovery-interval", () -> "10m");
        registry.add("indexing.worker.shutdown-grace-period", () -> "30s");
        // CPU 기반 BGE-M3의 실측 추론 시간을 운영 기본 Timeout과 분리한다.
        registry.add("embedding.server.read-timeout", () -> "2m");
    }

    @BeforeAll
    void setUpInfrastructure() throws Exception {
        apiClient = new LocalE2eApiClient(restTemplate);
        minioBucket = new LocalE2eMinioBucket(minioClient, TEST_BUCKET);
        minioBucket.create();
        accessToken = apiClient.loginAdmin();
        awaitCondition(
            "Worker가 Application Ready 뒤 등록되지 않았습니다.",
            () -> workerLifecycleManager.getWorkerId().isPresent()
        );
    }

    @AfterAll
    void cleanUpInfrastructure() throws Exception {
        // 1. Scheduler와 실행 Thread를 먼저 닫아 자원 정리 뒤 DB 접근이 재개되지 않게 한다.
        pollingScheduler.stopPolling();
        executionLifecycleManager.shutdown();
        workerLifecycleManager.stopWorker();

        // 2. Benchmark 전용 Bucket과 Schema만 제거해 기존 개발 Data를 보존한다.
        minioBucket.close();
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @Timeout(3_600)
    @DisplayName("실제 PDF DOCX 50 100문서의 전체 인덱싱 처리량과 완전성을 검증한다")
    void measurePdfDocxIndexingE2ELoad() throws Exception {
        // 1. 잘못된 DB나 외부 Service의 수치가 기록되지 않도록 환경 계약을 먼저 확인한다.
        EnvironmentFingerprint environment = validateEnvironment();
        List<ProfileRun> runs = new ArrayList<>();
        writeReport(new BenchmarkReport(environment, runs, List.of()));
        logJson("DOCUMENT_INDEXING_E2E_LOAD_ENV", environment);

        // 2. 실제 PDF·DOCX Parser, MinIO와 BGE-M3 경로를 예열하되 결과에는 포함하지 않는다.
        runWarmUp();

        // 3. 50·100문서의 균형 혼합을 반복 실행하며 완료된 Run을 즉시 보존한다.
        for (int documentCount : DOCUMENT_COUNTS) {
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                resetProfileState();
                ProfileRun run = runMeasuredProfile(documentCount, repetition);
                runs.add(run);
                logJson("DOCUMENT_INDEXING_E2E_LOAD_RESULT", run);
                writeReport(new BenchmarkReport(environment, runs, medians(runs)));
            }
        }

        // 4. 장비 내 변동을 줄인 Profile별 중앙값을 최종 결과와 Log에 남긴다.
        List<ProfileMedian> medians = medians(runs);
        for (ProfileMedian median : medians) {
            logJson("DOCUMENT_INDEXING_E2E_LOAD_MEDIAN", median);
        }
        writeReport(new BenchmarkReport(environment, runs, medians));
    }

    private EnvironmentFingerprint validateEnvironment() {
        String postgresVersion = jdbcTemplate.queryForObject("SHOW server_version", String.class);
        String pgvectorVersion = jdbcTemplate.queryForObject(
            "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
            String.class
        );
        Map<String, Object> model = jdbcTemplate.queryForMap(
            "SELECT model_name, dimension FROM embedding_models "
                + "WHERE is_active = TRUE AND is_searchable = TRUE"
        );
        JsonNode health = embeddingRestClient.get()
            .uri("/health")
            .retrieve()
            .body(JsonNode.class);

        assertThat(jdbcTemplate.queryForObject("SELECT current_schema()", String.class))
            .isEqualTo(TEST_SCHEMA);
        assertThat(postgresVersion).startsWith(EXPECTED_POSTGRES_VERSION_PREFIX);
        assertThat(pgvectorVersion).isEqualTo(EXPECTED_PGVECTOR_VERSION);
        assertThat(model.get("model_name").toString()).isEqualTo(EXPECTED_MODEL);
        assertThat(((Number) model.get("dimension")).intValue()).isEqualTo(EXPECTED_VECTOR_DIMENSION);
        assertThat(health).isNotNull();
        assertThat(health.path("status").asText()).isEqualTo("ok");
        assertThat(workerProperties.getMaxConcurrency()).isEqualTo(2);

        return new EnvironmentFingerprint(
            postgresVersion,
            pgvectorVersion,
            SpringBootVersion.getVersion(),
            EXPECTED_MODEL,
            EXPECTED_VECTOR_DIMENSION,
            embeddingBatchProperties.getBatchSize(),
            workerProperties.getMaxConcurrency(),
            workerProperties.getPollingInterval().toString(),
            UPLOADER_THREADS,
            WARM_UP_DOCUMENT_COUNT,
            DOCUMENT_COUNTS,
            REPETITIONS,
            SECTION_CHARACTER_COUNT,
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors()
        );
    }

    private void runWarmUp() throws Exception {
        resetProfileState();
        List<UploadMeasurement> uploads = uploadDocuments("warm-up", WARM_UP_DOCUMENT_COUNT);
        awaitIndexedAndIdle(uploads, "Warm-up");
        assertProfileInvariants(uploads);
        log.info("PDF DOCX E2E 부하 Benchmark 예열 완료. documentCount={}", WARM_UP_DOCUMENT_COUNT);
    }

    private ProfileRun runMeasuredProfile(int documentCount, int repetition) throws Exception {
        String profileName = "documents-" + documentCount + "-run-" + repetition;
        long profileStartedAt = System.nanoTime();
        List<UploadMeasurement> uploads = uploadDocuments(profileName, documentCount);
        long uploadCompletedAt = System.nanoTime();

        awaitIndexedAndIdle(uploads, profileName);
        long profileCompletedAt = System.nanoTime();
        ProfileData profileData = assertProfileInvariants(uploads);

        double elapsedSeconds = seconds(profileCompletedAt - profileStartedAt);
        double uploadSeconds = seconds(uploadCompletedAt - profileStartedAt);
        double queueDrainSeconds = seconds(profileCompletedAt - uploadCompletedAt);
        List<DocumentMeasurement> measurements = profileData.measurements();
        Map<DocumentFormat, FormatSummary> byFormat =
            DocumentIndexingE2ELoadStatistics.summarizeByFormat(measurements, elapsedSeconds);

        return new ProfileRun(
            documentCount,
            repetition,
            profileData.chunkCount(),
            profileData.embeddingCount(),
            elapsedSeconds,
            uploadSeconds,
            queueDrainSeconds,
            documentCount / elapsedSeconds,
            documentCount / elapsedSeconds * 60.0,
            profileData.chunkCount() / elapsedSeconds,
            profileData.embeddingCount() / elapsedSeconds,
            LatencySummary.from(measurements.stream().map(DocumentMeasurement::uploadMillis).toList()),
            LatencySummary.from(measurements.stream().map(DocumentMeasurement::queueMillis).toList()),
            LatencySummary.from(measurements.stream().map(DocumentMeasurement::processingMillis).toList()),
            LatencySummary.from(measurements.stream().map(DocumentMeasurement::e2eMillis).toList()),
            byFormat
        );
    }

    private List<UploadMeasurement> uploadDocuments(String profileName, int documentCount) throws Exception {
        List<PreparedDocument> documents = prepareDocuments(profileName, documentCount);
        int threadCount = Math.min(UPLOADER_THREADS, documentCount);
        ExecutorService uploader = Executors.newFixedThreadPool(threadCount);
        List<Future<UploadMeasurement>> futures = new ArrayList<>(documentCount);

        try {
            // 1. PDF와 DOCX를 교대로 병렬 제출해 형식이 한쪽에 몰리지 않는 PENDING Queue를 만든다.
            for (PreparedDocument document : documents) {
                futures.add(uploader.submit(() -> {
                    long startedAt = System.nanoTime();
                    UploadedDocument upload = apiClient.upload(accessToken, document.payload());
                    return new UploadMeasurement(
                        document.format(),
                        document.payload(),
                        upload,
                        seconds(System.nanoTime() - startedAt) * 1_000.0
                    );
                }));
            }
            uploader.shutdown();

            // 2. 하나의 Profile 마감 시각을 공유해 Future마다 Timeout이 누적되지 않게 한다.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROFILE_TIMEOUT_SECONDS);
            List<UploadMeasurement> uploads = new ArrayList<>(documentCount);
            for (Future<UploadMeasurement> future : futures) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    throw new AssertionError(profileName + " Upload 공통 제한 시간을 초과했습니다.");
                }
                uploads.add(future.get(remainingNanos, TimeUnit.NANOSECONDS));
            }
            return List.copyOf(uploads);
        } finally {
            uploader.shutdownNow();
            uploader.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private List<PreparedDocument> prepareDocuments(String profileName, int documentCount)
        throws IOException {
        List<PreparedDocument> documents = new ArrayList<>(documentCount);
        for (int index = 0; index < documentCount; index++) {
            DocumentFormat format = index % 2 == 0 ? DocumentFormat.PDF : DocumentFormat.DOCX;
            documents.add(new PreparedDocument(format, documentPayload(profileName, index, format)));
        }
        assertThat(documents.stream().filter(item -> item.format() == DocumentFormat.PDF).count())
            .isEqualTo(documentCount / 2L);
        assertThat(documents.stream().filter(item -> item.format() == DocumentFormat.DOCX).count())
            .isEqualTo(documentCount / 2L);
        return List.copyOf(documents);
    }

    private DocumentPayload documentPayload(
        String profileName,
        int documentIndex,
        DocumentFormat format
    ) throws IOException {
        String sequence = String.format(Locale.ROOT, "%04d", documentIndex);
        String baseName = profileName + "-" + sequence;
        if (format == DocumentFormat.PDF) {
            return LocalE2eDocumentFactory.pdf(
                baseName + ".pdf",
                "PDF E2E Load " + baseName,
                fixedLengthText(baseName, "PDF page one vector indexing"),
                fixedLengthText(baseName, "PDF page two semantic retrieval")
            );
        }
        return LocalE2eDocumentFactory.docx(
            baseName + ".docx",
            "DOCX E2E Load " + baseName,
            List.of(
                new DocumentSection(
                    "DOCX Pipeline " + baseName,
                    fixedLengthText(baseName, "DOCX section one worker pipeline")
                ),
                new DocumentSection(
                    "DOCX Search " + baseName,
                    fixedLengthText(baseName, "DOCX section two searchable version")
                )
            )
        );
    }

    private String fixedLengthText(String baseName, String subject) {
        String marker = "DocGrid " + baseName + " " + subject + ". ";
        String sentence = "Automatic workers parse real documents, store deterministic chunks, "
            + "generate BGE-M3 vectors, and switch the searchable version atomically. ";
        StringBuilder body = new StringBuilder(SECTION_CHARACTER_COUNT);
        body.append(marker);
        while (body.length() < SECTION_CHARACTER_COUNT) {
            body.append(sentence);
        }
        body.setLength(SECTION_CHARACTER_COUNT);
        return body.toString();
    }

    private void awaitIndexedAndIdle(List<UploadMeasurement> uploads, String profileName)
        throws InterruptedException {
        awaitCondition(
            profileName + " Profile이 완료되지 않았습니다. " + jobSnapshot(uploads),
            () -> indexedJobCount(uploads) == uploads.size() && executionSlotPool.getActiveSlots() == 0
        );
    }

    private ProfileData assertProfileInvariants(List<UploadMeasurement> uploads) throws Exception {
        List<DocumentMeasurement> measurements = new ArrayList<>(uploads.size());
        Map<DocumentFormat, List<Integer>> chunksByFormat = new EnumMap<>(DocumentFormat.class);
        for (DocumentFormat format : DocumentFormat.values()) {
            chunksByFormat.put(format, new ArrayList<>());
        }
        int totalChunks = 0;
        int totalEmbeddings = 0;

        for (UploadMeasurement measurement : uploads) {
            UploadedDocument upload = measurement.upload();
            // 1. 실제 MinIO Object의 위치, 크기와 형식별 Content-Type을 DB와 교차 검증한다.
            assertStoredObject(measurement);

            // 2. Job과 검색 Version 전이가 끝났고 재시도 없이 한 Attempt만 성공했는지 확인한다.
            assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", upload.embeddingJobId()))
                .isEqualTo("INDEXED");
            assertThat(queryInteger(
                "SELECT retry_count FROM embedding_jobs WHERE id = ?",
                upload.embeddingJobId()
            )).isZero();
            assertThat(count(
                "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ?",
                upload.embeddingJobId()
            )).isOne();
            assertThat(count(
                "SELECT COUNT(*) FROM embedding_job_attempts "
                    + "WHERE embedding_job_id = ? AND status = 'SUCCESS'",
                upload.embeddingJobId()
            )).isOne();
            assertThat(queryString("SELECT status FROM documents WHERE id = ?", upload.documentId()))
                .isEqualTo("INDEXED");
            assertThat(queryLong("SELECT current_version_id FROM documents WHERE id = ?", upload.documentId()))
                .isEqualTo(upload.documentVersionId());
            assertThat(queryString(
                "SELECT status FROM document_versions WHERE id = ?",
                upload.documentVersionId()
            )).isEqualTo("INDEXED");

            // 3. Chunk와 Embedding이 일대일이고 모든 활성 Vector가 1024차원인지 확인한다.
            int chunkCount = count(
                "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ?",
                upload.documentVersionId()
            );
            int embeddingCount = count(
                "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ?",
                upload.documentVersionId()
            );
            assertThat(chunkCount).isPositive();
            assertThat(embeddingCount).isEqualTo(chunkCount);
            assertThat(count(
                "SELECT COUNT(DISTINCT chunk_id) FROM embeddings WHERE document_version_id = ?",
                upload.documentVersionId()
            )).isEqualTo(embeddingCount);
            assertThat(queryInteger(
                "SELECT MIN(vector_dims(vector)) FROM embeddings WHERE document_version_id = ?",
                upload.documentVersionId()
            )).isEqualTo(EXPECTED_VECTOR_DIMENSION);
            assertThat(queryInteger(
                "SELECT MAX(vector_dims(vector)) FROM embeddings WHERE document_version_id = ?",
                upload.documentVersionId()
            )).isEqualTo(EXPECTED_VECTOR_DIMENSION);
            assertThat(count(
                "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ? AND status = 'ACTIVE'",
                upload.documentVersionId()
            )).isEqualTo(embeddingCount);
            assertFormatMetadata(measurement.format(), upload.documentVersionId(), chunkCount);

            // 4. 정상 Event 순서와 실패·Retry 부재를 확인한 뒤 단계별 지연을 수집한다.
            List<String> events = jdbcTemplate.queryForList(
                "SELECT event_type FROM indexing_events WHERE embedding_job_id = ? ORDER BY id",
                String.class,
                upload.embeddingJobId()
            );
            assertThat(events).containsSubsequence(
                "LOCKED",
                "PARSE_STARTED",
                "CHUNKED",
                "EMBEDDING_STARTED",
                "INDEXED"
            );
            assertThat(events).doesNotContain(
                "PARSE_FAILED",
                "EMBEDDING_FAILED",
                "LEASE_EXPIRED",
                "FAILED",
                "RETRY",
                "MANUAL_RETRY"
            );
            JobTiming timing = readJobTiming(upload.embeddingJobId());
            measurements.add(new DocumentMeasurement(
                measurement.format(),
                measurement.uploadMillis(),
                timing.queueWaitMillis(),
                timing.processingMillis(),
                timing.endToEndMillis(),
                chunkCount,
                embeddingCount
            ));
            chunksByFormat.get(measurement.format()).add(chunkCount);
            totalChunks += chunkCount;
            totalEmbeddings += embeddingCount;
        }

        // 5. 형식별 Fixture 분포, Object 수와 전체 Queue 소진 상태를 확인한다.
        DocumentIndexingE2ELoadStatistics.validateBalancedMix(measurements);
        for (List<Integer> chunkCounts : chunksByFormat.values()) {
            assertThat(chunkCounts).isNotEmpty();
            assertThat(chunkCounts).allMatch(count -> count.equals(chunkCounts.get(0)));
        }
        assertThat(minioBucket.objectKeys()).hasSize(uploads.size());
        assertThat(count(
            "SELECT COUNT(*) FROM embedding_jobs WHERE status IN ('PENDING', 'PROCESSING')"
        )).isZero();
        assertThat(workerLifecycleManager.getWorkerId()).isPresent();
        return new ProfileData(totalChunks, totalEmbeddings, List.copyOf(measurements));
    }

    private void assertStoredObject(UploadMeasurement measurement) throws Exception {
        StoredObject storedObject = jdbcTemplate.queryForObject(
            "SELECT bucket_name, object_key, content_type, file_size FROM file_objects WHERE id = ?",
            (resultSet, rowNumber) -> new StoredObject(
                resultSet.getString("bucket_name"),
                resultSet.getString("object_key"),
                resultSet.getString("content_type"),
                resultSet.getLong("file_size")
            ),
            measurement.upload().fileObjectId()
        );
        assertThat(storedObject).isNotNull();
        assertThat(storedObject.bucketName()).isEqualTo(TEST_BUCKET);
        assertThat(storedObject.contentType()).isEqualTo(measurement.payload().mediaType().toString());
        assertThat(storedObject.fileSize()).isEqualTo(measurement.payload().content().length);
        StatObjectResponse stat = minioBucket.stat(storedObject.objectKey());
        assertThat(stat.size()).isEqualTo(measurement.payload().content().length);
        assertThat(stat.contentType()).isEqualTo(measurement.payload().mediaType().toString());
    }

    private void assertFormatMetadata(DocumentFormat format, Long versionId, int chunkCount) {
        if (format == DocumentFormat.PDF) {
            assertThat(count(
                "SELECT COUNT(*) FROM document_chunks "
                    + "WHERE document_version_id = ? AND page_no IS NOT NULL",
                versionId
            )).isEqualTo(chunkCount);
            assertThat(count(
                "SELECT COUNT(DISTINCT page_no) FROM document_chunks WHERE document_version_id = ?",
                versionId
            )).isEqualTo(2);
            return;
        }
        assertThat(count(
            "SELECT COUNT(*) FROM document_chunks "
                + "WHERE document_version_id = ? AND NULLIF(BTRIM(section_title), '') IS NOT NULL",
            versionId
        )).isEqualTo(chunkCount);
        assertThat(count(
            "SELECT COUNT(DISTINCT section_title) FROM document_chunks WHERE document_version_id = ?",
            versionId
        )).isEqualTo(2);
    }

    private JobTiming readJobTiming(Long jobId) {
        return jdbcTemplate.queryForObject(
            """
            SELECT job.created_at AS job_created_at,
                   MIN(event.occurred_at) FILTER (WHERE event.event_type = 'LOCKED') AS first_locked_at,
                   MIN(event.occurred_at) FILTER (WHERE event.event_type = 'INDEXED') AS indexed_at
            FROM embedding_jobs job
            JOIN indexing_events event ON event.embedding_job_id = job.id
            WHERE job.id = ?
            GROUP BY job.id, job.created_at
            """,
            (resultSet, rowNumber) -> {
                LocalDateTime createdAt = resultSet.getTimestamp("job_created_at").toLocalDateTime();
                LocalDateTime lockedAt = resultSet.getTimestamp("first_locked_at").toLocalDateTime();
                LocalDateTime indexedAt = resultSet.getTimestamp("indexed_at").toLocalDateTime();
                double queueWaitMillis = millis(Duration.between(createdAt, lockedAt));
                double processingMillis = millis(Duration.between(lockedAt, indexedAt));
                double endToEndMillis = millis(Duration.between(createdAt, indexedAt));
                assertThat(queueWaitMillis).isGreaterThanOrEqualTo(0.0);
                assertThat(processingMillis).isGreaterThanOrEqualTo(0.0);
                assertThat(endToEndMillis).isGreaterThanOrEqualTo(processingMillis);
                return new JobTiming(queueWaitMillis, processingMillis, endToEndMillis);
            },
            jobId
        );
    }

    private void resetProfileState() throws Exception {
        awaitCondition(
            "이전 Profile의 Worker 실행 슬롯이 반환되지 않았습니다.",
            () -> executionSlotPool.getActiveSlots() == 0
        );
        minioBucket.clear();
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                indexing_events,
                embedding_job_attempts,
                embeddings,
                document_chunks,
                embedding_jobs,
                document_versions,
                documents,
                file_objects
            RESTART IDENTITY CASCADE
            """);
    }

    private int indexedJobCount(List<UploadMeasurement> uploads) {
        String placeholders = String.join(",", uploads.stream().map(upload -> "?").toList());
        Object[] jobIds = uploads.stream().map(item -> item.upload().embeddingJobId()).toArray();
        return count(
            "SELECT COUNT(*) FROM embedding_jobs WHERE status = 'INDEXED' AND id IN ("
                + placeholders + ")",
            jobIds
        );
    }

    private String jobSnapshot(List<UploadMeasurement> uploads) {
        String placeholders = String.join(",", uploads.stream().map(upload -> "?").toList());
        Object[] jobIds = uploads.stream().map(item -> item.upload().embeddingJobId()).toArray();
        return jdbcTemplate.queryForList(
            "SELECT id || ':' || status || ':retry=' || retry_count || ':error=' "
                + "|| COALESCE(error_code, 'none') FROM embedding_jobs WHERE id IN ("
                + placeholders + ") ORDER BY id",
            String.class,
            jobIds
        ).toString();
    }

    private List<ProfileMedian> medians(List<ProfileRun> runs) {
        List<ProfileMedian> medians = new ArrayList<>();
        for (int documentCount : DOCUMENT_COUNTS) {
            List<ProfileRun> profileRuns = runs.stream()
                .filter(run -> run.documentCount() == documentCount)
                .toList();
            if (profileRuns.isEmpty()) {
                continue;
            }
            Map<DocumentFormat, FormatMedian> formatMedians = new EnumMap<>(DocumentFormat.class);
            for (DocumentFormat format : DocumentFormat.values()) {
                formatMedians.put(format, formatMedian(profileRuns, format));
            }
            medians.add(new ProfileMedian(
                documentCount,
                profileRuns.size(),
                median(profileRuns, ProfileRun::documentsPerSecond),
                median(profileRuns, ProfileRun::documentsPerMinute),
                median(profileRuns, ProfileRun::chunksPerSecond),
                median(profileRuns, ProfileRun::embeddingsPerSecond),
                median(profileRuns, ProfileRun::totalElapsedSeconds),
                median(profileRuns, ProfileRun::uploadElapsedSeconds),
                median(profileRuns, ProfileRun::queueDrainSeconds),
                median(profileRuns, run -> run.uploadLatency().p95Millis()),
                median(profileRuns, run -> run.queueWait().p95Millis()),
                median(profileRuns, run -> run.processing().p95Millis()),
                median(profileRuns, run -> run.endToEnd().p95Millis()),
                Map.copyOf(formatMedians)
            ));
        }
        return List.copyOf(medians);
    }

    private FormatMedian formatMedian(List<ProfileRun> runs, DocumentFormat format) {
        return new FormatMedian(
            median(runs, run -> run.byFormat().get(format).documentsPerMinute()),
            median(runs, run -> run.byFormat().get(format).chunksPerSecond()),
            median(runs, run -> run.byFormat().get(format).uploadLatencyMillis().p95()),
            median(runs, run -> run.byFormat().get(format).queueLatencyMillis().p95()),
            median(runs, run -> run.byFormat().get(format).processingLatencyMillis().p95()),
            median(runs, run -> run.byFormat().get(format).e2eLatencyMillis().p95())
        );
    }

    private double median(List<ProfileRun> runs, ProfileMetric metric) {
        return WorkerIndexingThroughputStatistics.median(runs.stream().map(metric::value).toList());
    }

    private void writeReport(BenchmarkReport report) throws IOException {
        Path parent = OUTPUT_PATH.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(OUTPUT_PATH.toFile(), report);
    }

    private void logJson(String prefix, Object value) throws JsonProcessingException {
        log.info("{} {}", prefix, objectMapper.writeValueAsString(value));
    }

    private void awaitCondition(String failureMessage, CheckedCondition condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROFILE_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        throw new AssertionError(failureMessage);
    }

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
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

    private static double seconds(long nanoseconds) {
        return nanoseconds / 1_000_000_000.0;
    }

    private static double millis(Duration duration) {
        return duration.toNanos() / 1_000_000.0;
    }

    private static int positiveIntegerProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        int parsed = value == null ? defaultValue : Integer.parseInt(value.trim());
        if (parsed < 1) {
            throw new IllegalArgumentException(name + "은 1 이상이어야 합니다.");
        }
        return parsed;
    }

    private static int positiveEvenIntegerProperty(String name, int defaultValue) {
        int parsed = positiveIntegerProperty(name, defaultValue);
        if (parsed % 2 != 0) {
            throw new IllegalArgumentException(name + "은 PDF DOCX 균형을 위해 짝수여야 합니다.");
        }
        return parsed;
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        long parsed = value == null ? defaultValue : Long.parseLong(value.trim());
        if (parsed < 1L) {
            throw new IllegalArgumentException(name + "은 1 이상이어야 합니다.");
        }
        return parsed;
    }

    private static List<Integer> positiveEvenIntegerListProperty(
        String name,
        List<Integer> defaults
    ) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaults;
        }
        List<Integer> parsed = List.of(value.split(",")).stream()
            .map(String::trim)
            .map(Integer::parseInt)
            .distinct()
            .toList();
        if (parsed.isEmpty() || parsed.stream().anyMatch(item -> item < 2 || item % 2 != 0)) {
            throw new IllegalArgumentException(name + "에는 2 이상의 짝수만 사용할 수 있습니다.");
        }
        return parsed;
    }

    /** 실행 환경과 Workload 설정을 Secret 없이 재현할 수 있는 지문이다. */
    private record EnvironmentFingerprint(
        String postgresVersion,
        String pgvectorVersion,
        String springBootVersion,
        String embeddingModel,
        int vectorDimension,
        int embeddingBatchSize,
        int workerMaxConcurrency,
        String workerPollingInterval,
        int uploaderThreads,
        int warmUpDocumentCount,
        List<Integer> documentCounts,
        int repetitions,
        int sectionCharacterCount,
        String osName,
        String osArchitecture,
        int availableProcessors
    ) {
    }

    /** 한 Profile 반복의 전체·형식별 처리량과 단계별 지연 원시 결과다. */
    private record ProfileRun(
        int documentCount,
        int repetition,
        int chunkCount,
        int embeddingCount,
        double totalElapsedSeconds,
        double uploadElapsedSeconds,
        double queueDrainSeconds,
        double documentsPerSecond,
        double documentsPerMinute,
        double chunksPerSecond,
        double embeddingsPerSecond,
        LatencySummary uploadLatency,
        LatencySummary queueWait,
        LatencySummary processing,
        LatencySummary endToEnd,
        Map<DocumentFormat, FormatSummary> byFormat
    ) {
    }

    /** 같은 문서 수 Profile 반복의 전체·형식별 지표 중앙값이다. */
    private record ProfileMedian(
        int documentCount,
        int completedRepetitions,
        double documentsPerSecond,
        double documentsPerMinute,
        double chunksPerSecond,
        double embeddingsPerSecond,
        double totalElapsedSeconds,
        double uploadElapsedSeconds,
        double queueDrainSeconds,
        double uploadP95Millis,
        double queueWaitP95Millis,
        double processingP95Millis,
        double endToEndP95Millis,
        Map<DocumentFormat, FormatMedian> byFormat
    ) {
    }

    /** 한 문서 형식의 핵심 처리량과 꼬리 지연 중앙값을 비교 가능하게 요약한다. */
    private record FormatMedian(
        double documentsPerMinute,
        double chunksPerSecond,
        double uploadP95Millis,
        double queueP95Millis,
        double processingP95Millis,
        double e2eP95Millis
    ) {
    }

    /** 한 지연 분포의 중앙값, 꼬리 지연과 최댓값을 밀리초로 보존한다. */
    private record LatencySummary(
        double p50Millis,
        double p95Millis,
        double p99Millis,
        double maxMillis
    ) {

        private static LatencySummary from(List<Double> samples) {
            return new LatencySummary(
                WorkerIndexingThroughputStatistics.percentile(samples, 50.0),
                WorkerIndexingThroughputStatistics.percentile(samples, 95.0),
                WorkerIndexingThroughputStatistics.percentile(samples, 99.0),
                samples.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
            );
        }
    }

    /** 형식과 Payload를 Upload 실행 전 순서와 함께 보존한다. */
    private record PreparedDocument(DocumentFormat format, DocumentPayload payload) {
    }

    /** 한 실제 HTTP Upload 결과에 형식·Payload·지연을 연결한다. */
    private record UploadMeasurement(
        DocumentFormat format,
        DocumentPayload payload,
        UploadedDocument upload,
        double uploadMillis
    ) {
    }

    /** MinIO Object 검증에 필요한 DB 저장 위치·형식·크기다. */
    private record StoredObject(
        String bucketName,
        String objectKey,
        String contentType,
        long fileSize
    ) {
    }

    /** Profile 검증에서 수집한 전체 저장량과 문서별 측정값이다. */
    private record ProfileData(
        int chunkCount,
        int embeddingCount,
        List<DocumentMeasurement> measurements
    ) {
    }

    /** 한 Job의 Queue, 실제 처리와 전체 지연을 밀리초로 표현한다. */
    private record JobTiming(double queueWaitMillis, double processingMillis, double endToEndMillis) {
    }

    /** 완료된 Profile과 중앙값을 환경 지문과 함께 JSON으로 저장하는 최상위 결과다. */
    private record BenchmarkReport(
        EnvironmentFingerprint environment,
        List<ProfileRun> runs,
        List<ProfileMedian> medians
    ) {
    }

    /** Profile 중앙값을 계산할 실수 지표를 선택한다. */
    @FunctionalInterface
    private interface ProfileMetric {
        double value(ProfileRun run);
    }

    /** 제한 시간 동안 반복 평가할 DB·Worker 완료 조건이다. */
    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate();
    }
}
