package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.DocgridApplication;
import com.opensource.docgrid.domain.embedding.config.EmbeddingBatchProperties;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerLifecycleManager;
import com.opensource.docgrid.e2e.LocalE2eApiClient.UploadedDocument;
import com.opensource.docgrid.e2e.LocalE2eDocumentFactory.DocumentPayload;
import com.opensource.docgrid.e2e.WorkerHorizontalScalingStatistics.WorkerProfile;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;

/**
 * 실제 PostgreSQL 17·pgvector·MinIO·BGE-M3에서 Worker 수와 Worker별 실행 Slot 확장성을 측정한다.
 *
 * <p>HTTP 접수만 담당하는 Coordinator와 Production Worker Lifecycle을 실행하는 독립 Spring Context를
 * 분리한다. 모든 Worker는 같은 Job Queue를 DB Claim으로 경쟁하며 처리량, 지연, 실제 Worker 분포와
 * 데이터 불변식을 함께 검증한다. 실제 외부 인프라를 점유하므로 전용 Gradle Task에서만 실행한다.
 */
@Slf4j
@Tag("integration")
@Tag("worker-horizontal-scaling")
@ActiveProfiles({"test", "minio-integration"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Worker 수·실행 Slot 수평 확장 Benchmark")
class WorkerHorizontalScalingBenchmark {

    private static final String EXECUTION_ID = UUID.randomUUID().toString().replace("-", "");
    // PostgreSQL 식별자 63자 제한 안에서 별도 Gradle 실행이 Schema를 공유하지 않도록 격리한다.
    private static final String TEST_SCHEMA = "docgrid_worker_horizontal_"
        + EXECUTION_ID.substring(0, 24);
    private static final String TEST_BUCKET = "docgrid-worker-horizontal-" + EXECUTION_ID;
    private static final String TEST_JWT_SECRET =
        "docgrid-worker-horizontal-scaling-test-secret-key-2026";
    private static final String EXPECTED_POSTGRES_VERSION_PREFIX = "17.";
    private static final String EXPECTED_PGVECTOR_VERSION = "0.8.1";
    private static final String EXPECTED_MODEL = "BAAI/bge-m3";
    private static final int EXPECTED_VECTOR_DIMENSION = 1024;
    private static final List<WorkerProfile> DEFAULT_PROFILES = List.of(
        new WorkerProfile(1, 1),
        new WorkerProfile(1, 2),
        new WorkerProfile(2, 1),
        new WorkerProfile(2, 2),
        new WorkerProfile(4, 2)
    );
    private static final List<WorkerProfile> PROFILES =
        WorkerHorizontalScalingStatistics.parseProfiles(
            System.getProperty("worker.horizontal.scaling.profiles"),
            DEFAULT_PROFILES
        );
    private static final int DOCUMENT_CHARACTER_COUNT = positiveIntegerProperty(
        "worker.horizontal.scaling.document-characters",
        6_400
    );
    private static final int DOCUMENT_COUNT = positiveIntegerProperty(
        "worker.horizontal.scaling.document-count",
        16
    );
    private static final int REPETITIONS = positiveIntegerProperty(
        "worker.horizontal.scaling.repetitions",
        2
    );
    private static final int WARM_UP_DOCUMENT_COUNT = positiveIntegerProperty(
        "worker.horizontal.scaling.warm-up-documents",
        2
    );
    private static final int UPLOADER_THREADS = positiveIntegerProperty(
        "worker.horizontal.scaling.uploader-threads",
        8
    );
    private static final long PROFILE_TIMEOUT_SECONDS = positiveLongProperty(
        "worker.horizontal.scaling.profile-timeout-seconds",
        600L
    );
    private static final long POLLING_SLEEP_MILLIS = positiveLongProperty(
        "worker.horizontal.scaling.status-polling-ms",
        100L
    );
    private static final Path OUTPUT_PATH = Path.of(System.getProperty(
        "worker.horizontal.scaling.output",
        "build/reports/worker-horizontal-scaling/worker-horizontal-scaling.json"
    ));

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MinioClient minioClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmbeddingBatchProperties embeddingBatchProperties;
    @Autowired @Qualifier("embeddingRestClient") private RestClient embeddingRestClient;

    private LocalE2eApiClient apiClient;
    private LocalE2eMinioBucket minioBucket;
    private String accessToken;
    private String coordinatorJdbcUrl;
    private WorkerCluster currentCluster;

    @DynamicPropertySource
    static void configureCoordinatorEnvironment(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("minio.bucket", () -> TEST_BUCKET);
        // Coordinator는 HTTP 접수와 검증만 담당하고 Job Claim 경쟁에는 참여하지 않는다.
        registry.add("indexing.worker.enabled", () -> "false");
        registry.add("embedding.server.read-timeout", () -> "2m");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
    }

    @BeforeAll
    void setUpInfrastructure() throws Exception {
        apiClient = new LocalE2eApiClient(restTemplate);
        minioBucket = new LocalE2eMinioBucket(minioClient, TEST_BUCKET);
        minioBucket.create();
        accessToken = apiClient.loginAdmin();
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            // DynamicPropertySource로 완성된 Schema 포함 URL을 독립 Worker Context에도 그대로 전달한다.
            coordinatorJdbcUrl = connection.getMetaData().getURL();
        }
        resetAllBenchmarkState();
    }

    @AfterAll
    void cleanUpInfrastructure() throws Exception {
        // 1. 실패 중단 경로에서도 독립 Worker Context를 먼저 닫아 DB 재접근을 차단한다.
        if (currentCluster != null) {
            currentCluster.close();
            currentCluster = null;
        }

        // 2. Benchmark가 만든 Bucket과 Schema만 제거해 기존 로컬 개발 Data를 보존한다.
        if (minioBucket != null) {
            minioBucket.close();
        }
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @Timeout(3_600)
    @DisplayName("Worker 수와 Worker별 실행 Slot 조합의 전체 인덱싱 확장성을 반복 측정한다")
    void measureWorkerAndExecutionSlotHorizontalScaling() throws Exception {
        // 1. 환경과 Workload 계약을 기록한 뒤 각 Profile을 독립 Worker Cluster로 실행한다.
        EnvironmentFingerprint environment = validateEnvironment();
        List<ProfileRun> runs = new ArrayList<>();
        writeReport(new BenchmarkReport(environment, runs, List.of()));
        logJson("WORKER_HORIZONTAL_SCALING_ENV", environment);

        for (WorkerProfile profile : PROFILES) {
            resetAllBenchmarkState();
            currentCluster = startWorkerCluster(profile);
            try {
                runWarmUp(profile, currentCluster);

                // 2. 같은 문서 분포를 반복 처리해 Worker 수와 Slot 수 외의 입력 차이를 줄인다.
                for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                    resetJobState(currentCluster);
                    ProfileRun run = runMeasuredProfile(profile, repetition, currentCluster);
                    runs.add(run);
                    logJson("WORKER_HORIZONTAL_SCALING_RESULT", run);
                    writeReport(new BenchmarkReport(environment, runs, medians(runs)));
                }
            } finally {
                WorkerCluster completedCluster = currentCluster;
                completedCluster.close();
                awaitWorkersStopped(completedCluster);
                currentCluster = null;
            }
        }

        // 3. 1 Worker × 1 Slot 중앙값 대비 Speedup과 전체 Slot 기준 효율을 계산한다.
        List<ProfileMedian> medians = medians(runs);
        for (ProfileMedian median : medians) {
            logJson("WORKER_HORIZONTAL_SCALING_MEDIAN", median);
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

        return new EnvironmentFingerprint(
            postgresVersion,
            pgvectorVersion,
            SpringBootVersion.getVersion(),
            EXPECTED_MODEL,
            EXPECTED_VECTOR_DIMENSION,
            embeddingBatchProperties.getBatchSize(),
            PROFILES,
            DOCUMENT_COUNT,
            REPETITIONS,
            WARM_UP_DOCUMENT_COUNT,
            DOCUMENT_CHARACTER_COUNT,
            UPLOADER_THREADS,
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors()
        );
    }

    private WorkerCluster startWorkerCluster(WorkerProfile profile) throws InterruptedException {
        List<ConfigurableApplicationContext> contexts = new ArrayList<>(profile.workerCount());
        List<WorkerHandle> workers = new ArrayList<>(profile.workerCount());

        try {
            // Worker Context를 순차 시작해 같은 Schema의 Flyway 검증이 서로 경합하지 않게 한다.
            for (int index = 1; index <= profile.workerCount(); index++) {
                String workerName = profile.name() + "-worker-" + String.format(Locale.ROOT, "%02d", index);
                String poolName = "worker-horizontal-" + profile.name() + "-" + index;
                ConfigurableApplicationContext context = new SpringApplicationBuilder(DocgridApplication.class)
                    // 제품 Swagger 설정까지 포함한 실제 배포 형태를 유지하되 임의 포트로 충돌을 막는다.
                    .web(WebApplicationType.SERVLET)
                    .profiles("test", "minio-integration")
                    .properties(
                        "spring.main.banner-mode=off",
                        "spring.jmx.enabled=false"
                    )
                    .initializers(applicationContext -> TestPropertyValues.of(
                        "TEST_DB_SCHEMA=" + TEST_SCHEMA,
                        "spring.datasource.url=" + coordinatorJdbcUrl,
                        "jwt.secret=" + TEST_JWT_SECRET,
                        "minio.bucket=" + TEST_BUCKET,
                        "indexing.worker.enabled=true",
                        "indexing.worker.name=" + workerName,
                        "indexing.worker.polling-interval=50ms",
                        "indexing.worker.heartbeat-interval=1s",
                        "indexing.worker.dead-threshold=2m",
                        "indexing.worker.max-concurrency=" + profile.slotsPerWorker(),
                        "indexing.worker.lease-duration=2m",
                        "indexing.worker.lease-renewal-interval=10s",
                        "indexing.worker.lease-recovery-interval=10m",
                        "indexing.worker.shutdown-grace-period=30s",
                        "embedding.server.read-timeout=2m",
                        "server.port=0",
                        "spring.application.name=" + workerName,
                        "spring.datasource.hikari.pool-name=" + poolName,
                        "spring.datasource.hikari.maximum-pool-size="
                            + Math.max(4, profile.slotsPerWorker() + 2)
                    ).applyTo(applicationContext))
                    .run();
                contexts.add(context);

                WorkerLifecycleManager lifecycleManager = context.getBean(WorkerLifecycleManager.class);
                WorkerExecutionSlotPool slotPool = context.getBean(WorkerExecutionSlotPool.class);
                IndexingWorkerProperties properties = context.getBean(IndexingWorkerProperties.class);
                awaitCondition(
                    workerName + "가 Application Ready 뒤 등록되지 않았습니다.",
                    () -> lifecycleManager.getWorkerId().isPresent()
                );
                assertThat(properties.getMaxConcurrency()).isEqualTo(profile.slotsPerWorker());
                assertThat(slotPool.getCapacity()).isEqualTo(profile.slotsPerWorker());
                workers.add(new WorkerHandle(
                    workerName,
                    lifecycleManager.getWorkerId().orElseThrow(),
                    slotPool
                ));
            }

            WorkerCluster cluster = new WorkerCluster(contexts, workers);
            assertRegisteredWorkers(cluster);
            return cluster;
        } catch (RuntimeException | InterruptedException | AssertionError exception) {
            closeContexts(contexts);
            throw exception;
        }
    }

    private void runWarmUp(WorkerProfile profile, WorkerCluster cluster) throws Exception {
        resetJobState(cluster);
        List<UploadedDocument> uploads = uploadDocuments(
            profile.name() + "-warm-up",
            WARM_UP_DOCUMENT_COUNT
        );
        awaitIndexedAndIdle(uploads, profile.name() + " Warm-up", cluster);
        // 예열은 Cache와 Model 준비가 목적이므로 Worker 분산 자체는 본 측정에서만 강제한다.
        assertProfileInvariants(uploads, cluster, false);
        log.info(
            "Worker 수평 확장 Benchmark 예열을 완료했습니다. profile={}, documentCount={}",
            profile.name(),
            WARM_UP_DOCUMENT_COUNT
        );
    }

    private ProfileRun runMeasuredProfile(
        WorkerProfile profile,
        int repetition,
        WorkerCluster cluster
    ) throws Exception {
        String runName = profile.name() + "-run-" + repetition;
        long profileStartedAt = System.nanoTime();
        List<UploadedDocument> uploads = uploadDocuments(runName, DOCUMENT_COUNT);
        long uploadCompletedAt = System.nanoTime();

        awaitIndexedAndIdle(uploads, runName, cluster);
        long profileCompletedAt = System.nanoTime();
        ProfileData profileData = assertProfileInvariants(uploads, cluster, true);

        double elapsedSeconds = seconds(profileCompletedAt - profileStartedAt);
        double uploadSeconds = seconds(uploadCompletedAt - profileStartedAt);
        double queueDrainSeconds = seconds(profileCompletedAt - uploadCompletedAt);
        return new ProfileRun(
            profile.name(),
            profile.workerCount(),
            profile.slotsPerWorker(),
            profile.totalSlots(),
            repetition,
            DOCUMENT_COUNT,
            profileData.chunkCount(),
            profileData.embeddingCount(),
            elapsedSeconds,
            uploadSeconds,
            queueDrainSeconds,
            DOCUMENT_COUNT / elapsedSeconds,
            DOCUMENT_COUNT / elapsedSeconds * 60.0,
            profileData.chunkCount() / elapsedSeconds,
            profileData.embeddingCount() / elapsedSeconds,
            profileData.workerDistribution(),
            profileData.workerDistribution().size(),
            LatencySummary.from(profileData.timings().stream().map(JobTiming::queueWaitMillis).toList()),
            LatencySummary.from(profileData.timings().stream().map(JobTiming::processingMillis).toList()),
            LatencySummary.from(profileData.timings().stream().map(JobTiming::endToEndMillis).toList())
        );
    }

    private List<UploadedDocument> uploadDocuments(String runName, int documentCount) throws Exception {
        int threadCount = Math.min(UPLOADER_THREADS, documentCount);
        ExecutorService uploader = Executors.newFixedThreadPool(threadCount);
        List<Future<UploadedDocument>> futures = new ArrayList<>(documentCount);

        try {
            // 1. Upload를 병렬 제출해 모든 Worker 실행 슬롯이 경쟁할 PENDING Queue를 빠르게 만든다.
            for (int index = 0; index < documentCount; index++) {
                int documentIndex = index;
                futures.add(uploader.submit(() -> apiClient.upload(
                    accessToken,
                    scalingDocument(runName, documentIndex)
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

    private DocumentPayload scalingDocument(String runName, int documentIndex) {
        String marker = String.format(
            Locale.ROOT,
            "DocGrid horizontal scaling document %04d. ",
            documentIndex
        );
        String sentence = "Distributed workers claim pending jobs, parse deterministic text, "
            + "store chunks, generate BGE-M3 vectors, and switch the searchable version atomically. ";
        StringBuilder body = new StringBuilder(DOCUMENT_CHARACTER_COUNT);
        body.append(marker);
        while (body.length() < DOCUMENT_CHARACTER_COUNT) {
            body.append(sentence);
        }
        body.setLength(DOCUMENT_CHARACTER_COUNT);

        String fileName = runName + "-" + String.format(Locale.ROOT, "%04d", documentIndex) + ".txt";
        return LocalE2eDocumentFactory.text(fileName, "Horizontal Scaling " + fileName, body.toString());
    }

    private void awaitIndexedAndIdle(
        List<UploadedDocument> uploads,
        String runName,
        WorkerCluster cluster
    ) throws InterruptedException {
        awaitCondition(
            runName + " Profile이 완료되지 않았습니다. jobs=" + jobSnapshot(uploads)
                + ", workers=" + workerSnapshot(cluster),
            () -> indexedJobCount(uploads) == uploads.size() && cluster.allSlotsReturned()
        );
    }

    private ProfileData assertProfileInvariants(
        List<UploadedDocument> uploads,
        WorkerCluster cluster,
        boolean requireMultipleWorkerParticipation
    ) {
        List<JobTiming> timings = new ArrayList<>(uploads.size());
        List<Integer> chunkCounts = new ArrayList<>(uploads.size());
        Map<String, Integer> workerDistribution = new LinkedHashMap<>();
        Set<Long> clusterWorkerIds = cluster.workerIds();
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
            SuccessfulAttempt attempt = readSuccessfulAttempt(upload.embeddingJobId());
            assertThat(clusterWorkerIds).contains(attempt.workerId());
            workerDistribution.merge(attempt.workerName(), 1, Integer::sum);
            assertThat(queryString("SELECT status FROM documents WHERE id = ?", upload.documentId()))
                .isEqualTo("INDEXED");
            assertThat(queryLong("SELECT current_version_id FROM documents WHERE id = ?", upload.documentId()))
                .isEqualTo(upload.documentVersionId());
            assertThat(queryString(
                "SELECT status FROM document_versions WHERE id = ?",
                upload.documentVersionId()
            )).isEqualTo("INDEXED");

            // 2. Chunk와 Embedding Set이 일대일이며 Vector 차원과 유한값 계약을 지키는지 확인한다.
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
                "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ? "
                    + "AND (LOWER(vector::text) LIKE '%nan%' "
                    + "OR LOWER(vector::text) LIKE '%infinity%')",
                upload.documentVersionId()
            )).isZero();
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

        // 4. Profile 전체 Queue와 실행 슬롯이 비었으며 다중 Worker가 실제 처리에 참여했는지 확인한다.
        assertThat(chunkCounts).allMatch(count -> count.equals(chunkCounts.get(0)));
        assertThat(count(
            "SELECT COUNT(*) FROM embedding_jobs WHERE status IN ('PENDING', 'PROCESSING')"
        )).isZero();
        assertThat(workerDistribution.values().stream().mapToInt(Integer::intValue).sum())
            .isEqualTo(uploads.size());
        if (requireMultipleWorkerParticipation && cluster.workerCount() > 1) {
            assertThat(workerDistribution.size()).isGreaterThanOrEqualTo(2);
        }
        assertRegisteredWorkers(cluster);
        return new ProfileData(
            chunkCounts.stream().mapToInt(Integer::intValue).sum(),
            totalEmbeddings,
            Map.copyOf(workerDistribution),
            List.copyOf(timings)
        );
    }

    private SuccessfulAttempt readSuccessfulAttempt(Long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT attempt.worker_node_id, worker.worker_name "
                + "FROM embedding_job_attempts attempt "
                + "JOIN worker_nodes worker ON worker.id = attempt.worker_node_id "
                + "WHERE attempt.embedding_job_id = ? AND attempt.status = 'SUCCESS'",
            (resultSet, rowNumber) -> new SuccessfulAttempt(
                resultSet.getLong("worker_node_id"),
                resultSet.getString("worker_name")
            ),
            jobId
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

    private void assertRegisteredWorkers(WorkerCluster cluster) {
        for (WorkerHandle worker : cluster.workers()) {
            assertThat(queryString("SELECT worker_name FROM worker_nodes WHERE id = ?", worker.id()))
                .isEqualTo(worker.name());
            assertThat(queryString("SELECT status FROM worker_nodes WHERE id = ?", worker.id()))
                .isIn("ACTIVE", "IDLE");
            assertThat(worker.slotPool().getCapacity()).isPositive();
        }
    }

    private void awaitWorkersStopped(WorkerCluster cluster) throws InterruptedException {
        String placeholders = String.join(",", cluster.workers().stream().map(worker -> "?").toList());
        Object[] workerIds = cluster.workers().stream().map(WorkerHandle::id).toArray();
        awaitCondition(
            "종료한 Worker가 STOPPED 상태로 전환되지 않았습니다: " + workerSnapshot(cluster),
            () -> count(
                "SELECT COUNT(*) FROM worker_nodes WHERE status = 'STOPPED' AND id IN ("
                    + placeholders + ")",
                workerIds
            ) == cluster.workerCount()
        );
    }

    private void resetJobState(WorkerCluster cluster) throws Exception {
        awaitCondition(
            "이전 Profile의 Worker 실행 Slot이 반환되지 않았습니다: " + workerSnapshot(cluster),
            cluster::allSlotsReturned
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

    private void resetAllBenchmarkState() throws Exception {
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
                file_objects,
                worker_nodes
            RESTART IDENTITY CASCADE
            """);
    }

    private int indexedJobCount(List<UploadedDocument> uploads) {
        String placeholders = String.join(",", uploads.stream().map(upload -> "?").toList());
        Object[] jobIds = uploads.stream().map(UploadedDocument::embeddingJobId).toArray();
        return count(
            "SELECT COUNT(*) FROM embedding_jobs WHERE status = 'INDEXED' AND id IN ("
                + placeholders + ")",
            jobIds
        );
    }

    private String jobSnapshot(List<UploadedDocument> uploads) {
        String placeholders = String.join(",", uploads.stream().map(upload -> "?").toList());
        Object[] jobIds = uploads.stream().map(UploadedDocument::embeddingJobId).toArray();
        return jdbcTemplate.queryForList(
            "SELECT id || ':' || status || ':retry=' || retry_count || ':error=' "
                + "|| COALESCE(error_code, 'none') FROM embedding_jobs WHERE id IN ("
                + placeholders + ") ORDER BY id",
            String.class,
            jobIds
        ).toString();
    }

    private String workerSnapshot(WorkerCluster cluster) {
        String placeholders = String.join(",", cluster.workers().stream().map(worker -> "?").toList());
        Object[] workerIds = cluster.workers().stream().map(WorkerHandle::id).toArray();
        return jdbcTemplate.queryForList(
            "SELECT id || ':' || worker_name || ':' || status FROM worker_nodes WHERE id IN ("
                + placeholders + ") ORDER BY id",
            String.class,
            workerIds
        ).toString();
    }

    private List<ProfileMedian> medians(List<ProfileRun> runs) {
        Map<String, Double> throughputMedians = new LinkedHashMap<>();
        for (WorkerProfile profile : PROFILES) {
            List<ProfileRun> profileRuns = runs.stream()
                .filter(run -> run.profileName().equals(profile.name()))
                .toList();
            if (!profileRuns.isEmpty()) {
                throughputMedians.put(
                    profile.name(),
                    median(profileRuns, ProfileRun::documentsPerSecond)
                );
            }
        }

        Double baseline = throughputMedians.get(new WorkerProfile(1, 1).name());
        if (baseline == null) {
            return List.of();
        }

        List<ProfileMedian> result = new ArrayList<>();
        for (WorkerProfile profile : PROFILES) {
            List<ProfileRun> profileRuns = runs.stream()
                .filter(run -> run.profileName().equals(profile.name()))
                .toList();
            if (profileRuns.isEmpty()) {
                continue;
            }
            double documentsPerSecond = throughputMedians.get(profile.name());
            double speedup = WorkerHorizontalScalingStatistics.speedup(baseline, documentsPerSecond);
            result.add(new ProfileMedian(
                profile.name(),
                profile.workerCount(),
                profile.slotsPerWorker(),
                profile.totalSlots(),
                profileRuns.size(),
                documentsPerSecond,
                median(profileRuns, ProfileRun::documentsPerMinute),
                median(profileRuns, ProfileRun::chunksPerSecond),
                median(profileRuns, ProfileRun::embeddingsPerSecond),
                median(profileRuns, ProfileRun::totalElapsedSeconds),
                median(profileRuns, ProfileRun::uploadElapsedSeconds),
                median(profileRuns, ProfileRun::queueDrainSeconds),
                median(profileRuns, ProfileRun::participatingWorkers),
                median(profileRuns, run -> run.queueWait().p95Millis()),
                median(profileRuns, run -> run.processing().p95Millis()),
                median(profileRuns, run -> run.endToEnd().p95Millis()),
                speedup,
                WorkerHorizontalScalingStatistics.scalingEfficiency(speedup, profile.totalSlots())
            ));
        }
        return List.copyOf(result);
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

    private void awaitCondition(String failureMessage, CheckedCondition condition)
        throws InterruptedException {
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

    private static void closeContexts(List<ConfigurableApplicationContext> contexts) {
        for (int index = contexts.size() - 1; index >= 0; index--) {
            contexts.get(index).close();
        }
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

    /** Secret을 제외하고 실행 환경과 Workload를 재현하는 지문이다. */
    private record EnvironmentFingerprint(
        String postgresVersion,
        String pgvectorVersion,
        String springBootVersion,
        String embeddingModel,
        int vectorDimension,
        int embeddingBatchSize,
        List<WorkerProfile> profiles,
        int documentCount,
        int repetitions,
        int warmUpDocumentCount,
        int documentCharacterCount,
        int uploaderThreads,
        String osName,
        String osArchitecture,
        int availableProcessors
    ) {
    }

    /** 한 Worker·Slot Profile 반복의 처리량, 지연과 실제 처리 분포 원시 결과다. */
    private record ProfileRun(
        String profileName,
        int workerCount,
        int slotsPerWorker,
        int totalSlots,
        int repetition,
        int documentCount,
        int chunkCount,
        int embeddingCount,
        double totalElapsedSeconds,
        double uploadElapsedSeconds,
        double queueDrainSeconds,
        double documentsPerSecond,
        double documentsPerMinute,
        double chunksPerSecond,
        double embeddingsPerSecond,
        Map<String, Integer> workerDistribution,
        int participatingWorkers,
        LatencySummary queueWait,
        LatencySummary processing,
        LatencySummary endToEnd
    ) {
    }

    /** 같은 Profile 반복 중앙값과 1×1 Baseline 대비 수평 확장 지표다. */
    private record ProfileMedian(
        String profileName,
        int workerCount,
        int slotsPerWorker,
        int totalSlots,
        int completedRepetitions,
        double documentsPerSecond,
        double documentsPerMinute,
        double chunksPerSecond,
        double embeddingsPerSecond,
        double totalElapsedSeconds,
        double uploadElapsedSeconds,
        double queueDrainSeconds,
        double participatingWorkersMedian,
        double queueWaitP95Millis,
        double processingP95Millis,
        double endToEndP95Millis,
        double speedup,
        double scalingEfficiency
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

    /** Profile 검증에서 수집한 Chunk·Embedding 수, Worker 분포와 Job 단계별 시각이다. */
    private record ProfileData(
        int chunkCount,
        int embeddingCount,
        Map<String, Integer> workerDistribution,
        List<JobTiming> timings
    ) {
    }

    /** 한 Job의 Queue, 실제 처리와 전체 지연을 밀리초로 표현한다. */
    private record JobTiming(double queueWaitMillis, double processingMillis, double endToEndMillis) {
    }

    /** 성공한 Attempt가 실제 어느 Worker에서 처리됐는지 보존한다. */
    private record SuccessfulAttempt(Long workerId, String workerName) {
    }

    /** 한 Worker Context의 DB 식별자와 실행 Slot Pool을 묶는다. */
    private record WorkerHandle(String name, Long id, WorkerExecutionSlotPool slotPool) {
    }

    /**
     * 한 Profile 동안 유지되는 독립 Worker Context 집합의 시작·실행 Slot·종료 경계를 관리한다.
     */
    private static final class WorkerCluster implements AutoCloseable {

        private final List<ConfigurableApplicationContext> contexts;
        private final List<WorkerHandle> workers;

        private WorkerCluster(
            List<ConfigurableApplicationContext> contexts,
            List<WorkerHandle> workers
        ) {
            this.contexts = List.copyOf(contexts);
            this.workers = List.copyOf(workers);
        }

        private List<WorkerHandle> workers() {
            return workers;
        }

        private int workerCount() {
            return workers.size();
        }

        private Set<Long> workerIds() {
            return workers.stream().map(WorkerHandle::id).collect(java.util.stream.Collectors.toSet());
        }

        private boolean allSlotsReturned() {
            return workers.stream().allMatch(worker ->
                worker.slotPool().getActiveSlots() == 0
                    && worker.slotPool().getAvailableSlots() == worker.slotPool().getCapacity()
            );
        }

        @Override
        public void close() {
            closeContexts(contexts);
        }
    }

    /** 환경, 원시 반복과 Profile 중앙값을 JSON으로 저장하는 최상위 결과다. */
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
