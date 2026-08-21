package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

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
import com.opensource.docgrid.e2e.LocalE2eApiClient.UploadedDocument;
import com.opensource.docgrid.e2e.LocalE2eDocumentFactory.DocumentPayload;
import com.opensource.docgrid.e2e.WorkerQueueBackpressureStatistics.LoadProfile;
import com.opensource.docgrid.e2e.WorkerQueueBackpressureStatistics.PressureState;
import com.opensource.docgrid.e2e.WorkerQueueBackpressureStatistics.QueueSample;
import com.opensource.docgrid.e2e.WorkerQueueBackpressureStatistics.QueueSummary;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;

/**
 * 실제 전체 인덱싱 Queue와 Hikari Connection Pool의 Backpressure 경계를 측정한다.
 *
 * <p>문서 수와 동시 업로드 수를 함께 높이며 Production HTTP 접수와 자동 Worker를 같은 Spring
 * Context에서 실행한다. 별도 Monitoring Connection으로 Queue를 관찰해 Application Pool을 점유하지
 * 않고, 처리량·Pool 대기·Queue AUC·데이터 정합성을 함께 기록한다.
 */
@Slf4j
@Tag("worker-queue-backpressure")
@ActiveProfiles({"test", "minio-integration"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("전체 인덱싱 Queue 및 DB Pool Backpressure Benchmark")
class WorkerQueueBackpressureBenchmark {

    private static final String EXECUTION_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String TEST_SCHEMA = "docgrid_worker_backpressure_"
        + EXECUTION_ID.substring(0, 24);
    private static final String TEST_BUCKET = "docgrid-worker-backpressure-" + EXECUTION_ID;
    private static final String WORKER_APPLICATION_NAME = "docgrid-worker-backpressure";
    private static final String MONITOR_APPLICATION_NAME = "docgrid-worker-backpressure-monitor";
    private static final String EXPECTED_POSTGRES_VERSION_PREFIX = "17.";
    private static final String EXPECTED_PGVECTOR_VERSION = "0.8.1";
    private static final String EXPECTED_MODEL = "BAAI/bge-m3";
    private static final int EXPECTED_VECTOR_DIMENSION = 1024;
    private static final List<LoadProfile> DEFAULT_PROFILES = List.of(
        new LoadProfile(16, 4),
        new LoadProfile(32, 8),
        new LoadProfile(64, 16),
        new LoadProfile(128, 32)
    );
    private static final List<LoadProfile> PROFILES =
        WorkerQueueBackpressureStatistics.parseProfiles(
            System.getProperty("worker.queue.backpressure.profiles"),
            DEFAULT_PROFILES
        );
    private static final int DOCUMENT_CHARACTER_COUNT = positiveIntegerProperty(
        "worker.queue.backpressure.document-characters",
        800
    );
    private static final int WARM_UP_DOCUMENT_COUNT = positiveIntegerProperty(
        "worker.queue.backpressure.warm-up-documents",
        4
    );
    private static final int REPETITIONS = positiveIntegerProperty(
        "worker.queue.backpressure.repetitions",
        2
    );
    private static final int DB_POOL_SIZE = positiveIntegerProperty(
        "worker.queue.backpressure.db-pool-size",
        4
    );
    private static final int WORKER_SLOTS = positiveIntegerProperty(
        "worker.queue.backpressure.worker-slots",
        8
    );
    private static final long CONNECTION_TIMEOUT_MILLIS = positiveLongProperty(
        "worker.queue.backpressure.connection-timeout-ms",
        30_000L
    );
    private static final long PROFILE_TIMEOUT_SECONDS = positiveLongProperty(
        "worker.queue.backpressure.profile-timeout-seconds",
        600L
    );
    private static final long SAMPLING_INTERVAL_MILLIS = positiveLongProperty(
        "worker.queue.backpressure.sampling-interval-ms",
        25L
    );
    private static final long POLLING_SLEEP_MILLIS = positiveLongProperty(
        "worker.queue.backpressure.status-polling-ms",
        100L
    );
    private static final Path OUTPUT_PATH = Path.of(System.getProperty(
        "worker.queue.backpressure.output",
        "build/reports/worker-queue-backpressure/worker-queue-backpressure.json"
    ));

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
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
        registry.add("jwt.secret", () -> "docgrid-worker-backpressure-test-secret-key-2026");
        registry.add("storage.bucket", () -> TEST_BUCKET);
        registry.add("indexing.worker.enabled", () -> "true");
        registry.add("indexing.worker.name", () -> "worker-queue-backpressure-benchmark");
        registry.add("indexing.worker.polling-interval", () -> "25ms");
        registry.add("indexing.worker.heartbeat-interval", () -> "1s");
        registry.add("indexing.worker.dead-threshold", () -> "2m");
        registry.add("indexing.worker.max-concurrency", () -> WORKER_SLOTS);
        registry.add("indexing.worker.lease-duration", () -> "2m");
        registry.add("indexing.worker.lease-renewal-interval", () -> "10s");
        registry.add("indexing.worker.lease-recovery-interval", () -> "10m");
        registry.add("indexing.worker.shutdown-grace-period", () -> "30s");
        registry.add("embedding.document.read-timeout", () -> "2m");

        // HTTP 접수와 Worker가 의도적으로 작은 같은 Pool을 경쟁하도록 측정 변수를 고정한다.
        registry.add("spring.datasource.hikari.pool-name", () -> "worker-backpressure-pool");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> DB_POOL_SIZE);
        registry.add("spring.datasource.hikari.minimum-idle", () -> DB_POOL_SIZE);
        registry.add("spring.datasource.hikari.connection-timeout", () -> CONNECTION_TIMEOUT_MILLIS);
        registry.add(
            "spring.datasource.hikari.data-source-properties.ApplicationName",
            () -> WORKER_APPLICATION_NAME
        );
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
        preWarmConnectionPool();
    }

    @AfterAll
    void cleanUpInfrastructure() throws Exception {
        // 1. 실행 Thread를 먼저 닫아 Bucket과 Schema 정리 뒤 DB 접근이 재개되지 않게 한다.
        pollingScheduler.stopPolling();
        executionLifecycleManager.shutdown();
        workerLifecycleManager.stopWorker();

        // 2. Benchmark가 만든 Bucket과 Schema만 제거해 다른 로컬 개발 데이터를 보존한다.
        if (minioBucket != null) {
            minioBucket.close();
        }
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    // 기본 8회 실행 × Profile 제한 600초에 예열과 사후 정합성 검증 예산을 더해 Test 상한을 둔다.
    @Timeout(6_000)
    @DisplayName("문서 수와 동시 업로드 증가에 따른 Queue·Hikari Pool 압력을 측정한다")
    void measureQueueAndConnectionPoolBackpressure() throws Exception {
        // 1. 실제 환경과 측정 변수를 검증하고 빈 결과 파일을 먼저 남겨 중단 실행도 식별하게 한다.
        EnvironmentFingerprint environment = validateEnvironment();
        List<ProfileRun> runs = new ArrayList<>();
        writeReport(report(environment, runs));
        logJson("WORKER_QUEUE_BACKPRESSURE_ENV", environment);

        // 2. Migration, HTTP, MinIO, BGE-M3와 Connection Pool 경로를 측정 전에 예열한다.
        runWarmUp();

        // 3. 부하를 오름차순으로 높이며 첫 붕괴가 관측되면 실행 중인 작업을 보호하고 종료한다.
        boolean collapsed = false;
        for (LoadProfile profile : PROFILES) {
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                resetProfileState();
                ProfileRun run = runMeasuredProfile(profile, repetition);
                runs.add(run);
                logJson("WORKER_QUEUE_BACKPRESSURE_RESULT", run);
                writeReport(report(environment, runs));
                if (run.pressureState() == PressureState.COLLAPSED) {
                    collapsed = true;
                    break;
                }
            }
            if (collapsed) {
                break;
            }
        }

        // 4. Profile 중앙값과 최초 경계를 기계 판독 JSON과 사람이 읽는 Markdown 표로 함께 출력한다.
        BenchmarkReport finalReport = report(environment, runs);
        finalReport.profileMedians().forEach(median -> {
            try {
                logJson("WORKER_QUEUE_BACKPRESSURE_MEDIAN", median);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Profile 중앙값 JSON 직렬화에 실패했습니다.", exception);
            }
        });
        log.info("WORKER_QUEUE_BACKPRESSURE_TABLE\n{}", markdownTable(finalReport.profileMedians()));
        logJson("WORKER_QUEUE_BACKPRESSURE_THRESHOLDS", finalReport.thresholds());
        writeReport(finalReport);
        assertThat(runs).isNotEmpty();
    }

    private EnvironmentFingerprint validateEnvironment() throws Exception {
        HikariDataSource hikariDataSource = hikariDataSource();
        HikariPoolMXBean poolMxBean = hikariDataSource.getHikariPoolMXBean();
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
        assertThat(workerProperties.getMaxConcurrency()).isEqualTo(WORKER_SLOTS);
        assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(DB_POOL_SIZE);
        assertThat(hikariDataSource.getMinimumIdle()).isEqualTo(DB_POOL_SIZE);
        assertThat(hikariDataSource.getConnectionTimeout()).isEqualTo(CONNECTION_TIMEOUT_MILLIS);
        assertThat(poolMxBean).isNotNull();
        assertThat(poolMxBean.getTotalConnections()).isEqualTo(DB_POOL_SIZE);

        return new EnvironmentFingerprint(
            postgresVersion,
            pgvectorVersion,
            SpringBootVersion.getVersion(),
            EXPECTED_MODEL,
            EXPECTED_VECTOR_DIMENSION,
            embeddingBatchProperties.getBatchSize(),
            WORKER_SLOTS,
            DB_POOL_SIZE,
            CONNECTION_TIMEOUT_MILLIS,
            SAMPLING_INTERVAL_MILLIS,
            WARM_UP_DOCUMENT_COUNT,
            PROFILES,
            REPETITIONS,
            DOCUMENT_CHARACTER_COUNT,
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors()
        );
    }

    private void preWarmConnectionPool() throws Exception {
        HikariPoolMXBean poolMxBean = hikariDataSource().getHikariPoolMXBean();
        List<Connection> connections = new ArrayList<>(DB_POOL_SIZE);
        try {
            for (int index = 0; index < DB_POOL_SIZE; index++) {
                Connection connection = dataSource.getConnection();
                connections.add(connection);
                try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                    statement.execute();
                }
            }
        } finally {
            for (int index = connections.size() - 1; index >= 0; index--) {
                connections.get(index).close();
            }
        }
        awaitCondition(
            "Hikari Pool이 maximum-pool-size까지 예열되지 않았습니다.",
            () -> poolMxBean.getTotalConnections() == DB_POOL_SIZE
        );
    }

    private void runWarmUp() throws Exception {
        resetProfileState();
        LoadProfile warmUpProfile = new LoadProfile(WARM_UP_DOCUMENT_COUNT, WARM_UP_DOCUMENT_COUNT);
        List<UploadAttempt> attempts = uploadDocuments(warmUpProfile, "warm-up");
        assertThat(attempts).allMatch(UploadAttempt::succeeded);
        List<UploadedDocument> uploads = successfulUploads(attempts);
        assertThat(awaitProfileTerminal(uploads.size())).isTrue();
        ProfileData data = assertProfileInvariants(uploads, true);
        assertThat(data.indexedJobs()).isEqualTo(WARM_UP_DOCUMENT_COUNT);
        log.info("Queue Backpressure Benchmark 예열 완료. documentCount={}", WARM_UP_DOCUMENT_COUNT);
    }

    private ProfileRun runMeasuredProfile(LoadProfile profile, int repetition) throws Exception {
        String runName = profile.name() + "-run-" + repetition;
        long profileStartedAt = System.nanoTime();
        List<UploadAttempt> attempts;
        long uploadCompletedAt;
        boolean terminal;
        long profileCompletedAt;
        PoolQueueObservation observation;

        try (PoolQueueSampler sampler = new PoolQueueSampler()) {
            sampler.start(profileStartedAt);
            attempts = uploadDocuments(profile, runName);
            uploadCompletedAt = System.nanoTime();
            terminal = awaitProfileTerminal(successfulUploads(attempts).size());
            profileCompletedAt = System.nanoTime();
            observation = sampler.stopAndSummarize();
        }

        List<UploadedDocument> uploads = successfulUploads(attempts);
        ProfileData profileData = assertProfileInvariants(uploads, terminal);
        int uploadFailures = attempts.size() - uploads.size();
        PressureState pressureState = WorkerQueueBackpressureStatistics.classify(
            DB_POOL_SIZE,
            observation.maxActiveConnections(),
            observation.maxAwaitingConnections(),
            uploadFailures,
            profileData.failedJobs(),
            profileData.incompleteJobs()
        );

        double elapsedSeconds = seconds(profileCompletedAt - profileStartedAt);
        double uploadSeconds = seconds(uploadCompletedAt - profileStartedAt);
        double queueDrainSeconds = seconds(profileCompletedAt - uploadCompletedAt);
        return new ProfileRun(
            profile,
            repetition,
            pressureState,
            attempts.size(),
            uploads.size(),
            uploadFailures,
            profileData.indexedJobs(),
            profileData.failedJobs(),
            profileData.incompleteJobs(),
            profileData.chunkCount(),
            profileData.embeddingCount(),
            elapsedSeconds,
            uploadSeconds,
            queueDrainSeconds,
            profileData.indexedJobs() / elapsedSeconds,
            profileData.indexedJobs() / elapsedSeconds * 60.0,
            profileData.chunkCount() / elapsedSeconds,
            profileData.embeddingCount() / elapsedSeconds,
            LatencySummary.from(attempts.stream().map(UploadAttempt::latencyMillis).toList()),
            LatencySummary.fromOrEmpty(
                profileData.timings().stream().map(JobTiming::queueWaitMillis).toList()
            ),
            LatencySummary.fromOrEmpty(
                profileData.timings().stream().map(JobTiming::processingMillis).toList()
            ),
            LatencySummary.fromOrEmpty(
                profileData.timings().stream().map(JobTiming::endToEndMillis).toList()
            ),
            observation
        );
    }

    private List<UploadAttempt> uploadDocuments(LoadProfile profile, String runName) throws Exception {
        int threadCount = Math.min(profile.uploaderThreads(), profile.documentCount());
        ExecutorService uploader = Executors.newFixedThreadPool(threadCount);
        List<Future<UploadAttempt>> futures = new ArrayList<>(profile.documentCount());
        long uploadsStartedAt = System.nanoTime();

        try {
            // 1. 같은 시작 구간에 HTTP 접수를 집중시켜 Worker와 Pool Connection을 실제로 경쟁시킨다.
            for (int index = 0; index < profile.documentCount(); index++) {
                int documentIndex = index;
                futures.add(uploader.submit(() -> uploadOne(runName, documentIndex)));
            }
            uploader.shutdown();

            // 2. 성공과 실패를 모두 결과로 보존해 Connection Timeout을 Benchmark 자체 실패로 숨기지 않는다.
            List<UploadAttempt> attempts = new ArrayList<>(profile.documentCount());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROFILE_TIMEOUT_SECONDS);
            for (Future<UploadAttempt> future : futures) {
                long remainingNanos = Math.max(0L, deadline - System.nanoTime());
                try {
                    attempts.add(future.get(remainingNanos, TimeUnit.NANOSECONDS));
                } catch (TimeoutException exception) {
                    future.cancel(true);
                    attempts.add(UploadAttempt.failure(
                        millis(System.nanoTime() - uploadsStartedAt),
                        exception.getClass().getSimpleName()
                    ));
                }
            }
            return List.copyOf(attempts);
        } finally {
            uploader.shutdownNow();
            uploader.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private UploadAttempt uploadOne(String runName, int documentIndex) {
        long startedAt = System.nanoTime();
        try {
            UploadedDocument upload = apiClient.upload(
                accessToken,
                backpressureDocument(runName, documentIndex)
            );
            return UploadAttempt.success(upload, millis(System.nanoTime() - startedAt));
        } catch (Exception | AssertionError failure) {
            return UploadAttempt.failure(
                millis(System.nanoTime() - startedAt),
                failure.getClass().getSimpleName()
            );
        }
    }

    private DocumentPayload backpressureDocument(String runName, int documentIndex) {
        String marker = String.format(
            Locale.ROOT,
            "DocGrid queue backpressure document %04d. ",
            documentIndex
        );
        String sentence = "Concurrent uploads create pending jobs while automatic workers parse text, "
            + "generate BGE-M3 vectors, and commit searchable document versions. ";
        StringBuilder body = new StringBuilder(DOCUMENT_CHARACTER_COUNT);
        body.append(marker);
        while (body.length() < DOCUMENT_CHARACTER_COUNT) {
            body.append(sentence);
        }
        body.setLength(DOCUMENT_CHARACTER_COUNT);

        String fileName = runName + "-" + String.format(Locale.ROOT, "%04d", documentIndex) + ".txt";
        return LocalE2eDocumentFactory.text(fileName, "Backpressure " + fileName, body.toString());
    }

    private boolean awaitProfileTerminal(int acceptedDocumentCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROFILE_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            int terminalJobs = count(
                "SELECT COUNT(*) FROM embedding_jobs WHERE status IN ('INDEXED', 'FAILED')"
            );
            if (terminalJobs == acceptedDocumentCount && executionSlotPool.getActiveSlots() == 0) {
                return true;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        return false;
    }

    private ProfileData assertProfileInvariants(List<UploadedDocument> uploads, boolean terminal) {
        int indexedJobs = 0;
        int failedJobs = 0;
        int incompleteJobs = 0;
        int totalChunks = 0;
        int totalEmbeddings = 0;
        List<Integer> chunkCounts = new ArrayList<>();
        List<JobTiming> timings = new ArrayList<>();

        for (UploadedDocument upload : uploads) {
            String status = queryString(
                "SELECT status FROM embedding_jobs WHERE id = ?",
                upload.embeddingJobId()
            );
            if ("FAILED".equals(status)) {
                failedJobs++;
                continue;
            }
            if (!"INDEXED".equals(status)) {
                incompleteJobs++;
                continue;
            }

            indexedJobs++;
            // 1. 성공 Job은 재시도 없이 한 Attempt만 완료하고 검색 Version을 전환해야 한다.
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

            // 2. Chunk와 Embedding이 일대일이고 모든 실제 Vector가 1024차원인지 확인한다.
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
            chunkCounts.add(chunkCount);
            totalChunks += chunkCount;
            totalEmbeddings += embeddingCount;

            // 3. 성공 Event 순서와 실제 Queue·처리·전체 지연 시각을 수집한다.
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
            timings.add(readJobTiming(upload.embeddingJobId()));
        }

        assertThat(indexedJobs + failedJobs + incompleteJobs).isEqualTo(uploads.size());
        if (!chunkCounts.isEmpty()) {
            assertThat(chunkCounts).allMatch(count -> count.equals(chunkCounts.get(0)));
        }
        if (terminal) {
            assertThat(incompleteJobs).isZero();
            assertThat(count(
                "SELECT COUNT(*) FROM embedding_jobs WHERE status IN ('PENDING', 'PROCESSING')"
            )).isZero();
            assertThat(executionSlotPool.getActiveSlots()).isZero();
        }
        return new ProfileData(
            indexedJobs,
            failedJobs,
            incompleteJobs,
            totalChunks,
            totalEmbeddings,
            List.copyOf(timings)
        );
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
            "이전 Profile의 Worker 실행 Slot이 반환되지 않았습니다.",
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

    private BenchmarkReport report(EnvironmentFingerprint environment, List<ProfileRun> runs) {
        List<ProfileMedian> medians = profileMedians(runs);
        return new BenchmarkReport(environment, List.copyOf(runs), medians, thresholds(runs));
    }

    private List<ProfileMedian> profileMedians(List<ProfileRun> runs) {
        List<ProfileMedian> medians = new ArrayList<>();
        for (LoadProfile profile : PROFILES) {
            List<ProfileRun> profileRuns = runs.stream()
                .filter(run -> run.profile().equals(profile))
                .toList();
            if (profileRuns.isEmpty()) {
                continue;
            }
            PressureState worstState = profileRuns.stream()
                .map(ProfileRun::pressureState)
                .max(Enum::compareTo)
                .orElseThrow();
            medians.add(new ProfileMedian(
                profile,
                profileRuns.size(),
                worstState,
                median(profileRuns, ProfileRun::documentsPerMinute),
                median(profileRuns, ProfileRun::totalElapsedSeconds),
                median(profileRuns, run -> run.uploadLatency().p95Millis()),
                median(profileRuns, run -> run.queueWait().p95Millis()),
                median(profileRuns, run -> run.observation().queue().queueDepthAucDocumentSeconds()),
                median(profileRuns, run -> run.observation().queue().averageQueueDepth()),
                profileRuns.stream().mapToInt(run -> run.observation().maxActiveConnections()).max().orElse(0),
                profileRuns.stream().mapToInt(run -> run.observation().maxAwaitingConnections()).max().orElse(0),
                median(profileRuns, run -> run.observation().poolSaturationSampleRatio()),
                median(profileRuns, run -> run.observation().poolAwaitingSampleRatio()),
                profileRuns.stream().mapToInt(ProfileRun::uploadFailures).sum(),
                profileRuns.stream().mapToInt(ProfileRun::failedJobs).sum(),
                profileRuns.stream().mapToInt(ProfileRun::incompleteJobs).sum()
            ));
        }
        return List.copyOf(medians);
    }

    private PressureThresholds thresholds(List<ProfileRun> runs) {
        return new PressureThresholds(
            firstProfile(runs, run -> run.observation().maxActiveConnections() == DB_POOL_SIZE),
            firstProfile(runs, run -> run.observation().maxAwaitingConnections() > 0),
            firstProfile(runs, run -> run.pressureState() == PressureState.COLLAPSED)
        );
    }

    private String firstProfile(List<ProfileRun> runs, ProfilePredicate predicate) {
        return runs.stream()
            .filter(predicate::matches)
            .map(run -> run.profile().name())
            .findFirst()
            .orElse("not-observed");
    }

    private double median(List<ProfileRun> runs, ProfileMetric metric) {
        return WorkerIndexingThroughputStatistics.median(runs.stream().map(metric::value).toList());
    }

    private String markdownTable(List<ProfileMedian> medians) {
        StringBuilder table = new StringBuilder();
        table.append("| Profile | 상태 | docs/min | Queue AUC | 평균 Queue | max active | max waiting | 포화 비율 | 대기 비율 |\n");
        table.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ProfileMedian median : medians) {
            table.append(String.format(
                Locale.ROOT,
                "| %s | %s | %.3f | %.3f | %.3f | %d | %d | %.4f | %.4f |%n",
                median.profile().name(),
                median.pressureState(),
                median.documentsPerMinute(),
                median.queueDepthAucDocumentSeconds(),
                median.averageQueueDepth(),
                median.maxActiveConnections(),
                median.maxAwaitingConnections(),
                median.poolSaturationSampleRatio(),
                median.poolAwaitingSampleRatio()
            ));
        }
        return table.toString();
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

    private List<UploadedDocument> successfulUploads(List<UploadAttempt> attempts) {
        return attempts.stream()
            .filter(UploadAttempt::succeeded)
            .map(UploadAttempt::upload)
            .toList();
    }

    private Connection openMonitoringConnection() throws SQLException {
        HikariDataSource hikariDataSource = hikariDataSource();
        Properties properties = new Properties();
        properties.setProperty("user", hikariDataSource.getUsername());
        properties.setProperty("password", hikariDataSource.getPassword());
        properties.setProperty("ApplicationName", MONITOR_APPLICATION_NAME);
        return DriverManager.getConnection(hikariDataSource.getJdbcUrl(), properties);
    }

    private HikariDataSource hikariDataSource() throws SQLException {
        return dataSource.unwrap(HikariDataSource.class);
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

    private static double millis(long nanoseconds) {
        return nanoseconds / 1_000_000.0;
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

    private static long positiveLongProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        long parsed = value == null ? defaultValue : Long.parseLong(value.trim());
        if (parsed < 1L) {
            throw new IllegalArgumentException(name + "은 1 이상이어야 합니다.");
        }
        return parsed;
    }

    /** Hikari Pool과 Job Queue를 Application Pool 밖에서 주기적으로 관찰한다. */
    private final class PoolQueueSampler implements AutoCloseable {

        private static final String QUEUE_STATUS_SQL = """
            SELECT COUNT(*) FILTER (WHERE status = 'PENDING') AS pending_jobs,
                   COUNT(*) FILTER (WHERE status = 'PROCESSING') AS processing_jobs,
                   COUNT(*) FILTER (WHERE status = 'INDEXED') AS indexed_jobs,
                   COUNT(*) FILTER (WHERE status = 'FAILED') AS failed_jobs
            FROM embedding_jobs
            """;

        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger maxActiveConnections = new AtomicInteger();
        private final AtomicInteger maxIdleConnections = new AtomicInteger();
        private final AtomicInteger maxTotalConnections = new AtomicInteger();
        private final AtomicInteger maxAwaitingConnections = new AtomicInteger();
        private final AtomicInteger saturatedSamples = new AtomicInteger();
        private final AtomicInteger awaitingSamples = new AtomicInteger();
        private final AtomicInteger sampleCount = new AtomicInteger();
        private final CountDownLatch firstSampleLatch = new CountDownLatch(1);
        private final List<QueueSample> queueSamples = new ArrayList<>();

        private ExecutorService executorService;
        private Future<?> samplerFuture;
        private Connection monitoringConnection;
        private PreparedStatement queueStatusStatement;
        private HikariPoolMXBean poolMxBean;
        private long profileStartedAt;
        private PoolQueueObservation summary;

        void start(long startedAt) throws Exception {
            profileStartedAt = startedAt;
            monitoringConnection = openMonitoringConnection();
            monitoringConnection.setReadOnly(true);
            queueStatusStatement = monitoringConnection.prepareStatement(QUEUE_STATUS_SQL);
            poolMxBean = hikariDataSource().getHikariPoolMXBean();
            executorService = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("worker-queue-backpressure-sampler");
                return thread;
            });

            // 첫 Sample을 확인한 뒤 업로드를 시작해 짧은 Profile도 0 상태부터 관측한다.
            running.set(true);
            samplerFuture = executorService.submit(this::sampleUntilStopped);
            if (!firstSampleLatch.await(10, TimeUnit.SECONDS)) {
                throw new TimeoutException("Queue Backpressure Sampler가 제한 시간 안에 시작되지 않았습니다.");
            }
            throwIfFailed();
        }

        PoolQueueObservation stopAndSummarize() throws Exception {
            if (summary != null) {
                return summary;
            }

            running.set(false);
            if (samplerFuture != null) {
                samplerFuture.get(10, TimeUnit.SECONDS);
            }
            throwIfFailed();
            // 마지막 종착 상태를 별도로 읽어 Queue AUC가 실제 소진 시점에서 끝나게 한다.
            sample();
            int totalSamples = sampleCount.get();
            summary = new PoolQueueObservation(
                maxActiveConnections.get(),
                maxIdleConnections.get(),
                maxTotalConnections.get(),
                maxAwaitingConnections.get(),
                saturatedSamples.get(),
                awaitingSamples.get(),
                totalSamples,
                WorkerQueueBackpressureStatistics.sampleRatio(saturatedSamples.get(), totalSamples),
                WorkerQueueBackpressureStatistics.sampleRatio(awaitingSamples.get(), totalSamples),
                WorkerQueueBackpressureStatistics.summarizeQueue(List.copyOf(queueSamples))
            );
            return summary;
        }

        private void sampleUntilStopped() {
            try {
                while (running.get()) {
                    sample();
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

        private void sample() throws SQLException {
            int active = poolMxBean.getActiveConnections();
            int idle = poolMxBean.getIdleConnections();
            int total = poolMxBean.getTotalConnections();
            int awaiting = poolMxBean.getThreadsAwaitingConnection();
            QueueSample queueSample;

            try (ResultSet resultSet = queueStatusStatement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Queue 상태 집계 결과가 없습니다.");
                }
                queueSample = new QueueSample(
                    System.nanoTime() - profileStartedAt,
                    resultSet.getInt("pending_jobs"),
                    resultSet.getInt("processing_jobs"),
                    resultSet.getInt("indexed_jobs"),
                    resultSet.getInt("failed_jobs")
                );
            }

            maxActiveConnections.accumulateAndGet(active, Math::max);
            maxIdleConnections.accumulateAndGet(idle, Math::max);
            maxTotalConnections.accumulateAndGet(total, Math::max);
            maxAwaitingConnections.accumulateAndGet(awaiting, Math::max);
            if (active == DB_POOL_SIZE) {
                saturatedSamples.incrementAndGet();
            }
            if (awaiting > 0) {
                awaitingSamples.incrementAndGet();
            }
            queueSamples.add(queueSample);
            sampleCount.incrementAndGet();
        }

        private void throwIfFailed() {
            Throwable samplingFailure = failure.get();
            if (samplingFailure != null) {
                throw new IllegalStateException("Queue와 Hikari Pool Sampling에 실패했습니다.", samplingFailure);
            }
        }

        @Override
        public void close() throws Exception {
            try {
                stopAndSummarize();
            } finally {
                if (queueStatusStatement != null) {
                    queueStatusStatement.close();
                }
                if (monitoringConnection != null) {
                    monitoringConnection.close();
                }
                if (executorService != null) {
                    executorService.shutdownNow();
                    executorService.awaitTermination(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    /** Secret 없이 재현할 수 있는 Database, Model, Pool과 부하 설정 지문이다. */
    private record EnvironmentFingerprint(
        String postgresVersion,
        String pgvectorVersion,
        String springBootVersion,
        String embeddingModel,
        int vectorDimension,
        int embeddingBatchSize,
        int workerSlots,
        int databasePoolSize,
        long connectionTimeoutMillis,
        long samplingIntervalMillis,
        int warmUpDocumentCount,
        List<LoadProfile> profiles,
        int repetitions,
        int documentCharacterCount,
        String osName,
        String osArchitecture,
        int availableProcessors
    ) {
    }

    /** 한 Profile 반복의 처리량, 실패, 지연과 Pool·Queue 관측 원시 결과다. */
    private record ProfileRun(
        LoadProfile profile,
        int repetition,
        PressureState pressureState,
        int attemptedUploads,
        int acceptedUploads,
        int uploadFailures,
        int indexedJobs,
        int failedJobs,
        int incompleteJobs,
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
        PoolQueueObservation observation
    ) {
    }

    /** 같은 부하 Profile 반복을 중앙값과 최악 상태로 요약한 비교 단위다. */
    private record ProfileMedian(
        LoadProfile profile,
        int completedRepetitions,
        PressureState pressureState,
        double documentsPerMinute,
        double totalElapsedSeconds,
        double uploadP95Millis,
        double queueWaitP95Millis,
        double queueDepthAucDocumentSeconds,
        double averageQueueDepth,
        int maxActiveConnections,
        int maxAwaitingConnections,
        double poolSaturationSampleRatio,
        double poolAwaitingSampleRatio,
        int uploadFailures,
        int failedJobs,
        int incompleteJobs
    ) {
    }

    /** Hikari Pool과 Queue sample 시계열의 한 Profile 요약이다. */
    private record PoolQueueObservation(
        int maxActiveConnections,
        int maxIdleConnections,
        int maxTotalConnections,
        int maxAwaitingConnections,
        int saturatedSamples,
        int awaitingSamples,
        int sampleCount,
        double poolSaturationSampleRatio,
        double poolAwaitingSampleRatio,
        QueueSummary queue
    ) {
    }

    /** 최초 Pool 포화, Connection 대기와 붕괴가 관측된 부하 Profile 이름이다. */
    private record PressureThresholds(
        String firstPoolSaturatedProfile,
        String firstPoolBackpressuredProfile,
        String firstCollapsedProfile
    ) {
    }

    /** Profile Upload 한 건의 성공 식별자 또는 실패 종류와 지연이다. */
    private record UploadAttempt(
        UploadedDocument upload,
        double latencyMillis,
        String failureType
    ) {

        private static UploadAttempt success(UploadedDocument upload, double latencyMillis) {
            return new UploadAttempt(upload, latencyMillis, null);
        }

        private static UploadAttempt failure(double latencyMillis, String failureType) {
            return new UploadAttempt(null, latencyMillis, failureType);
        }

        private boolean succeeded() {
            return upload != null;
        }
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

        private static LatencySummary fromOrEmpty(List<Double> samples) {
            return samples.isEmpty() ? new LatencySummary(0.0, 0.0, 0.0, 0.0) : from(samples);
        }
    }

    /** Profile 종료 시 Job 결과, Vector 수와 단계별 시각을 모은다. */
    private record ProfileData(
        int indexedJobs,
        int failedJobs,
        int incompleteJobs,
        int chunkCount,
        int embeddingCount,
        List<JobTiming> timings
    ) {
    }

    /** 한 성공 Job의 Queue, 처리와 전체 지연을 밀리초로 표현한다. */
    private record JobTiming(double queueWaitMillis, double processingMillis, double endToEndMillis) {
    }

    /** 환경, 원시 실행, Profile 중앙값과 최초 압력 경계를 저장하는 최상위 결과다. */
    private record BenchmarkReport(
        EnvironmentFingerprint environment,
        List<ProfileRun> runs,
        List<ProfileMedian> profileMedians,
        PressureThresholds thresholds
    ) {
    }

    /** Profile 중앙값을 계산할 실수 지표를 선택한다. */
    @FunctionalInterface
    private interface ProfileMetric {
        double value(ProfileRun run);
    }

    /** 최초 압력 경계 Profile을 선택할 조건이다. */
    @FunctionalInterface
    private interface ProfilePredicate {
        boolean matches(ProfileRun run);
    }

    /** 제한 시간 동안 반복 평가할 Worker 완료 조건이다. */
    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate();
    }
}
