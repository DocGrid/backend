package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.opensource.docgrid.e2e.LocalE2eApiClient.UploadedDocument;
import com.opensource.docgrid.e2e.LocalE2eDocumentFactory.DocumentPayload;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;

/**
 * 실제 PostgreSQL 17·pgvector·MinIO·BGE-M3에서 자동 Worker 전체 인덱싱 처리량을 측정한다.
 *
 * <p>결정적 TXT 문서를 실제 HTTP로 동시에 접수하고 Production Scheduler가 Queue를 소진하게 한다.
 * 각 Profile은 처리량과 단계별 지연을 기록한 뒤 Job, Attempt, Event, Chunk와 Vector 정합성을 함께
 * 검증한다. 실제 Infrastructure를 요구하므로 일반 회귀 테스트와 분리된 Tag에서만 실행한다.
 */
@Slf4j
@Tag("worker-indexing-throughput")
@ActiveProfiles({"test", "minio-integration"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("자동 Worker 전체 문서 인덱싱 처리량 Benchmark")
class WorkerIndexingThroughputBenchmark {

    private static final String EXECUTION_ID = UUID.randomUUID().toString().replace("-", "");
    // PostgreSQL 식별자 63자 제한 안에서 별도 Gradle 실행이 Schema를 공유하지 않도록 격리한다.
    private static final String TEST_SCHEMA = "docgrid_worker_indexing_throughput_"
        + EXECUTION_ID.substring(0, 24);
    private static final String TEST_BUCKET = "docgrid-worker-throughput-" + EXECUTION_ID;
    private static final String EXPECTED_POSTGRES_VERSION_PREFIX = "17.";
    private static final String EXPECTED_PGVECTOR_VERSION = "0.8.1";
    private static final String EXPECTED_MODEL = "BAAI/bge-m3";
    private static final int EXPECTED_VECTOR_DIMENSION = 1024;
    private static final int DOCUMENT_CHARACTER_COUNT = positiveIntegerProperty(
        "worker.indexing.throughput.document-characters",
        6_400
    );
    private static final int WARM_UP_DOCUMENT_COUNT = positiveIntegerProperty(
        "worker.indexing.throughput.warm-up-documents",
        4
    );
    private static final List<Integer> DOCUMENT_COUNTS = positiveIntegerListProperty(
        "worker.indexing.throughput.document-counts",
        List.of(16, 32)
    );
    private static final int REPETITIONS = positiveIntegerProperty(
        "worker.indexing.throughput.repetitions",
        3
    );
    private static final int UPLOADER_THREADS = positiveIntegerProperty(
        "worker.indexing.throughput.uploader-threads",
        4
    );
    private static final long PROFILE_TIMEOUT_SECONDS = positiveLongProperty(
        "worker.indexing.throughput.profile-timeout-seconds",
        600L
    );
    private static final long POLLING_SLEEP_MILLIS = positiveLongProperty(
        "worker.indexing.throughput.status-polling-ms",
        100L
    );
    private static final Path OUTPUT_PATH = Path.of(System.getProperty(
        "worker.indexing.throughput.output",
        "build/reports/worker-indexing-throughput/worker-indexing-throughput.json"
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
        registry.add("jwt.secret", () -> "docgrid-worker-indexing-throughput-test-secret-key-2026");
        registry.add("minio.bucket", () -> TEST_BUCKET);
        registry.add("indexing.worker.enabled", () -> "true");
        registry.add("indexing.worker.name", () -> "worker-indexing-throughput-benchmark");
        registry.add("indexing.worker.polling-interval", () -> "50ms");
        registry.add("indexing.worker.heartbeat-interval", () -> "1s");
        registry.add("indexing.worker.dead-threshold", () -> "2m");
        registry.add("indexing.worker.max-concurrency", () -> "2");
        registry.add("indexing.worker.lease-duration", () -> "2m");
        registry.add("indexing.worker.lease-renewal-interval", () -> "10s");
        registry.add("indexing.worker.lease-recovery-interval", () -> "10m");
        registry.add("indexing.worker.shutdown-grace-period", () -> "30s");
        // CPU 기반 BGE-M3의 실측 추론 시간을 5초 기본 운영 Timeout과 분리한다.
        registry.add("embedding.document.read-timeout", () -> "2m");
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
        // 1. Scheduler와 실행 Thread를 먼저 닫아 Profile 자원 정리 뒤 DB 접근이 재개되지 않게 한다.
        pollingScheduler.stopPolling();
        executionLifecycleManager.shutdown();
        workerLifecycleManager.stopWorker();

        // 2. Benchmark 전용 Bucket과 Schema만 제거해 기존 로컬 개발 Data를 보존한다.
        minioBucket.close();
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @Timeout(1_800)
    @DisplayName("실제 자동 Worker의 처리량과 Queue·처리·전체 지연을 반복 측정한다")
    void measureAutomaticWorkerIndexingThroughput() throws Exception {
        // 1. 잘못된 DB나 외부 Service에서 얻은 수치를 기록하지 않도록 환경 계약을 먼저 확인한다.
        EnvironmentFingerprint environment = validateEnvironment();
        List<ProfileRun> runs = new ArrayList<>();
        writeReport(new BenchmarkReport(environment, runs, List.of()));
        logJson("WORKER_INDEXING_THROUGHPUT_ENV", environment);

        // 2. Migration, HTTP, MinIO, Model과 주요 DB 경로를 예열하되 측정 결과에는 포함하지 않는다.
        runWarmUp();

        // 3. 같은 문서 분포로 Queue 크기와 반복만 바꿔 Profile 원시 결과를 수집한다.
        for (int documentCount : DOCUMENT_COUNTS) {
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                resetProfileState();
                ProfileRun run = runMeasuredProfile(documentCount, repetition);
                runs.add(run);
                logJson("WORKER_INDEXING_THROUGHPUT_RESULT", run);
                writeReport(new BenchmarkReport(environment, runs, medians(runs)));
            }
        }

        // 4. 장비 내 변동을 줄인 Profile별 중앙값을 최종 결과와 Log에 별도로 남긴다.
        List<ProfileMedian> medians = medians(runs);
        for (ProfileMedian median : medians) {
            logJson("WORKER_INDEXING_THROUGHPUT_MEDIAN", median);
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
            DOCUMENT_CHARACTER_COUNT,
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors()
        );
    }

    private void runWarmUp() throws Exception {
        resetProfileState();
        List<UploadedDocument> uploads = uploadDocuments("warm-up", WARM_UP_DOCUMENT_COUNT);
        awaitIndexedAndIdle(uploads, "Warm-up");
        assertProfileInvariants(uploads);
        log.info(
            "자동 Worker 처리량 Benchmark 예열을 완료했습니다. documentCount={}",
            WARM_UP_DOCUMENT_COUNT
        );
    }

    private ProfileRun runMeasuredProfile(int documentCount, int repetition) throws Exception {
        String profileName = "documents-" + documentCount + "-run-" + repetition;
        long profileStartedAt = System.nanoTime();
        List<UploadedDocument> uploads = uploadDocuments(profileName, documentCount);
        long uploadCompletedAt = System.nanoTime();

        awaitIndexedAndIdle(uploads, profileName);
        long profileCompletedAt = System.nanoTime();
        ProfileData profileData = assertProfileInvariants(uploads);

        double elapsedSeconds = seconds(profileCompletedAt - profileStartedAt);
        double uploadSeconds = seconds(uploadCompletedAt - profileStartedAt);
        double queueDrainSeconds = seconds(profileCompletedAt - uploadCompletedAt);
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
            LatencySummary.from(profileData.timings().stream().map(JobTiming::queueWaitMillis).toList()),
            LatencySummary.from(profileData.timings().stream().map(JobTiming::processingMillis).toList()),
            LatencySummary.from(profileData.timings().stream().map(JobTiming::endToEndMillis).toList())
        );
    }

    private List<UploadedDocument> uploadDocuments(String profileName, int documentCount) throws Exception {
        int threadCount = Math.min(UPLOADER_THREADS, documentCount);
        ExecutorService uploader = Executors.newFixedThreadPool(threadCount);
        List<Future<UploadedDocument>> futures = new ArrayList<>(documentCount);

        try {
            // 1. Upload를 병렬 제출해 Worker 슬롯이 유지될 만큼 빠르게 PENDING Queue를 만든다.
            for (int index = 0; index < documentCount; index++) {
                int documentIndex = index;
                futures.add(uploader.submit(() -> apiClient.upload(
                    accessToken,
                    throughputDocument(profileName, documentIndex)
                )));
            }
            uploader.shutdown();

            // 2. 각 HTTP 응답을 제한 시간 안에서 수집하고 원래 문서 순서를 보존한다.
            List<UploadedDocument> uploads = new ArrayList<>(documentCount);
            for (Future<UploadedDocument> future : futures) {
                uploads.add(future.get(PROFILE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return List.copyOf(uploads);
        } finally {
            uploader.shutdownNow();
            uploader.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private DocumentPayload throughputDocument(String profileName, int documentIndex) {
        String marker = String.format(
            Locale.ROOT,
            "DocGrid automatic worker throughput document %04d. ",
            documentIndex
        );
        String sentence = "Automatic workers claim pending jobs, parse deterministic text, "
            + "store chunks, generate BGE-M3 vectors, and switch the searchable version atomically. ";
        StringBuilder body = new StringBuilder(DOCUMENT_CHARACTER_COUNT);
        body.append(marker);
        while (body.length() < DOCUMENT_CHARACTER_COUNT) {
            body.append(sentence);
        }
        body.setLength(DOCUMENT_CHARACTER_COUNT);

        String fileName = profileName + "-" + String.format(Locale.ROOT, "%04d", documentIndex) + ".txt";
        return LocalE2eDocumentFactory.text(fileName, "Throughput " + fileName, body.toString());
    }

    private void awaitIndexedAndIdle(List<UploadedDocument> uploads, String profileName)
        throws InterruptedException {
        awaitCondition(
            profileName + " Profile이 완료되지 않았습니다. " + jobSnapshot(uploads),
            () -> indexedJobCount(uploads) == uploads.size() && executionSlotPool.getActiveSlots() == 0
        );
    }

    private ProfileData assertProfileInvariants(List<UploadedDocument> uploads) {
        List<JobTiming> timings = new ArrayList<>(uploads.size());
        List<Integer> chunkCounts = new ArrayList<>(uploads.size());
        int totalEmbeddings = 0;

        for (UploadedDocument upload : uploads) {
            // 1. Job과 검색 Version 전이가 끝났고 재시도 없이 한 Attempt만 성공했는지 확인한다.
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

            // 2. Chunk와 Embedding Set이 일대일이며 모든 Vector가 1024차원인지 확인한다.
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
            totalEmbeddings += embeddingCount;

            // 3. 정상 Event 순서와 실패·Retry 부재를 확인한 뒤 단계별 시각을 수집한다.
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
            timings.add(readJobTiming(upload.embeddingJobId()));
        }

        // 4. 같은 Profile의 문서별 Chunk 분포와 전체 Queue 소진 상태를 확인한다.
        assertThat(chunkCounts).allMatch(count -> count.equals(chunkCounts.get(0)));
        assertThat(count(
            "SELECT COUNT(*) FROM embedding_jobs WHERE status IN ('PENDING', 'PROCESSING')"
        )).isZero();
        assertThat(workerLifecycleManager.getWorkerId()).isPresent();
        return new ProfileData(
            chunkCounts.stream().mapToInt(Integer::intValue).sum(),
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

    private int indexedJobCount(List<UploadedDocument> uploads) {
        String placeholders = String.join(",", uploads.stream().map(upload -> "?").toList());
        Object[] jobIds = uploads.stream().map(UploadedDocument::embeddingJobId).toArray();
        return count(
            "SELECT COUNT(*) FROM embedding_jobs WHERE status = 'INDEXED' AND id IN (" + placeholders + ")",
            jobIds
        );
    }

    private String jobSnapshot(List<UploadedDocument> uploads) {
        String placeholders = String.join(",", uploads.stream().map(upload -> "?").toList());
        Object[] jobIds = uploads.stream().map(UploadedDocument::embeddingJobId).toArray();
        return jdbcTemplate.queryForList(
            "SELECT id || ':' || status || ':retry=' || retry_count || ':error=' "
                + "|| COALESCE(error_code, 'none') FROM embedding_jobs WHERE id IN (" + placeholders + ") "
                + "ORDER BY id",
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
                median(profileRuns, run -> run.queueWait().p95Millis()),
                median(profileRuns, run -> run.processing().p95Millis()),
                median(profileRuns, run -> run.endToEnd().p95Millis())
            ));
        }
        return List.copyOf(medians);
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

    private static long positiveLongProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        long parsed = value == null ? defaultValue : Long.parseLong(value.trim());
        if (parsed < 1L) {
            throw new IllegalArgumentException(name + "은 1 이상이어야 합니다.");
        }
        return parsed;
    }

    private static List<Integer> positiveIntegerListProperty(String name, List<Integer> defaults) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaults;
        }
        List<Integer> parsed = List.of(value.split(",")).stream()
            .map(String::trim)
            .map(Integer::parseInt)
            .distinct()
            .toList();
        if (parsed.isEmpty() || parsed.stream().anyMatch(item -> item < 1)) {
            throw new IllegalArgumentException(name + "에는 1 이상의 정수만 사용할 수 있습니다.");
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
        int documentCharacterCount,
        String osName,
        String osArchitecture,
        int availableProcessors
    ) {
    }

    /** 한 Profile 반복의 처리량과 단계별 지연 원시 결과다. */
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
        LatencySummary queueWait,
        LatencySummary processing,
        LatencySummary endToEnd
    ) {
    }

    /** 같은 문서 수 Profile의 반복 결과를 중앙값으로 요약한 비교 단위다. */
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
        double queueWaitP95Millis,
        double processingP95Millis,
        double endToEndP95Millis
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

    /** Profile 검증에서 수집한 전체 Chunk·Embedding 수와 Job 단계별 시각이다. */
    private record ProfileData(int chunkCount, int embeddingCount, List<JobTiming> timings) {
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
