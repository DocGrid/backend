package com.opensource.docgrid.domain.embedding.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 실제 PostgreSQL pgvector에서 저장 규모와 JDBC Batch Size별 Vector 저장 TPS를 비교한다.
 *
 * <p>제품 Table을 변경하지 않고 고유 Test Schema의 Probe Table만 사용한다. Vector 생성 비용은
 * 측정 전에 분리하며 Bind부터 HNSW Online 갱신과 Commit까지를 저장 경계로 측정한다.
 */
@Slf4j
@Tag("integration")
@Tag("vector-storage-performance")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Vector 저장 TPS·Batch Size Benchmark")
class VectorStoragePerformanceBenchmark {

    static final int VECTOR_DIMENSION = 1024;
    static final int HNSW_M = 16;
    static final int HNSW_EF_CONSTRUCTION = 64;
    private static final long VECTOR_SEED = 151L;
    private static final String TEST_SCHEMA = "docgrid_vector_storage_performance_test_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String PROBE_TABLE = "vector_storage_performance_probe";
    private static final String HNSW_INDEX = "vector_storage_performance_hnsw";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-vector-storage-performance-test-secret-key-2026");
        registry.add(
            "spring.datasource.hikari.data-source-properties.ApplicationName",
            () -> "docgrid-vector-storage-performance-test"
        );
    }

    @AfterAll
    void dropIsolatedSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @Timeout(3_600)
    @DisplayName("1천·1만·10만 건의 Vector 저장 TPS를 Batch Size별로 비교한다")
    void compareVectorStorageThroughputByScaleAndBatchSize() throws Exception {
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        List<String> vectorPool = createVectorPool(configuration.vectorPoolSize());
        List<ProfileResult> profiles = new ArrayList<>();

        createProbeTable();
        assertHnswIndexContract();
        try {
            int executionOrder = 0;
            for (int scaleIndex = 0; scaleIndex < configuration.rowCounts().size(); scaleIndex++) {
                int rowCount = configuration.rowCounts().get(scaleIndex);
                for (int batchSize : rotate(configuration.batchSizes(), scaleIndex)) {
                    profiles.add(runProfile(
                        rowCount,
                        batchSize,
                        executionOrder++,
                        configuration,
                        vectorPool
                    ));
                }
            }
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        }

        BenchmarkReport report = createReport(configuration, profiles);
        writeReport(configuration.outputPath(), report);
        assertThat(report.profiles()).hasSize(
            configuration.rowCounts().size() * configuration.batchSizes().size()
        );
    }

    private void createProbeTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        jdbcTemplate.execute(
            "CREATE TABLE " + PROBE_TABLE
                + " (id BIGINT PRIMARY KEY, vector vector(1024) NOT NULL)"
        );
        jdbcTemplate.execute(
            "CREATE INDEX " + HNSW_INDEX + " ON " + PROBE_TABLE
                + " USING hnsw (vector vector_cosine_ops) WITH (m = " + HNSW_M
                + ", ef_construction = " + HNSW_EF_CONSTRUCTION + ")"
        );
    }

    private ProfileResult runProfile(
        int rowCount,
        int batchSize,
        int executionOrder,
        BenchmarkConfiguration configuration,
        List<String> vectorPool
    ) throws SQLException {
        // 1. 측정 경로와 같은 Batch Size로 작은 입력을 먼저 적재해 Driver와 DB 경로를 준비한다.
        truncateProbeTable();
        insertVectors(
            Math.min(rowCount, configuration.warmUpRows()),
            batchSize,
            vectorPool
        );

        // 2. 각 Round는 빈 Table에서 시작하고 Bind부터 Commit까지 같은 경계로 측정한다.
        List<RoundMeasurement> rounds = new ArrayList<>();
        long tableBytes = 0L;
        long indexBytes = 0L;
        for (int round = 1; round <= configuration.measuredRuns(); round++) {
            truncateProbeTable();
            RoundMeasurement measurement = insertVectors(rowCount, batchSize, vectorPool);
            assertStoredVectors(rowCount);
            rounds.add(measurement);
            tableBytes = tableSize(PROBE_TABLE);
            indexBytes = relationSize(HNSW_INDEX);
        }

        // 3. Raw Round와 요약 TPS를 함께 남겨 작은 표본을 숨기지 않는다.
        ProfileResult result = new ProfileResult(
            rowCount,
            batchSize,
            executionOrder,
            expectedBatchExecutions(rowCount, batchSize),
            List.copyOf(rounds),
            TimingSummary.from(rounds.stream().map(RoundMeasurement::durationMillis).toList()),
            TimingSummary.from(rounds.stream().map(RoundMeasurement::rowsPerSecond).toList()),
            tableBytes,
            indexBytes,
            tableBytes + indexBytes
        );
        logProfile(result);
        return result;
    }

    private RoundMeasurement insertVectors(int rowCount, int batchSize, List<String> vectorPool)
        throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + PROBE_TABLE + " (id, vector) VALUES (?, CAST(? AS vector))"
            )) {
                long startedAt = System.nanoTime();
                int pendingRows = 0;
                int batchExecutions = 0;
                int acknowledgedRows = 0;
                for (int row = 0; row < rowCount; row++) {
                    statement.setLong(1, row + 1L);
                    statement.setString(2, vectorPool.get(row % vectorPool.size()));
                    statement.addBatch();
                    pendingRows++;
                    if (pendingRows == batchSize) {
                        acknowledgedRows += executeBatch(statement, pendingRows);
                        batchExecutions++;
                        pendingRows = 0;
                    }
                }
                if (pendingRows > 0) {
                    acknowledgedRows += executeBatch(statement, pendingRows);
                    batchExecutions++;
                }
                connection.commit();
                double durationMillis = nanosToMillis(System.nanoTime() - startedAt);

                assertThat(acknowledgedRows).isEqualTo(rowCount);
                assertThat(batchExecutions).isEqualTo(expectedBatchExecutions(rowCount, batchSize));
                return new RoundMeasurement(
                    durationMillis,
                    rowsPerSecond(rowCount, durationMillis),
                    batchExecutions,
                    acknowledgedRows
                );
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private int executeBatch(PreparedStatement statement, int expectedRows) throws SQLException {
        int[] updateCounts = statement.executeBatch();
        assertThat(updateCounts).hasSize(expectedRows);
        assertThat(updateCounts).doesNotContain(java.sql.Statement.EXECUTE_FAILED);
        return updateCounts.length;
    }

    private void assertStoredVectors(int expectedRows) {
        StoredVectorSummary summary = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) AS row_count, MIN(vector_dims(vector)) AS min_dims, "
                + "MAX(vector_dims(vector)) AS max_dims FROM " + PROBE_TABLE,
            (resultSet, rowNumber) -> new StoredVectorSummary(
                resultSet.getLong("row_count"),
                resultSet.getInt("min_dims"),
                resultSet.getInt("max_dims")
            )
        );
        assertThat(summary).isNotNull();
        assertThat(summary.rowCount()).isEqualTo(expectedRows);
        assertThat(summary.minDimensions()).isEqualTo(VECTOR_DIMENSION);
        assertThat(summary.maxDimensions()).isEqualTo(VECTOR_DIMENSION);
    }

    private void assertHnswIndexContract() {
        String indexDefinition = jdbcTemplate.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() "
                + "AND tablename = ? AND indexname = ?",
            String.class,
            PROBE_TABLE,
            HNSW_INDEX
        );
        assertThat(indexDefinition)
            .contains("USING hnsw")
            .contains("vector_cosine_ops");
    }

    private void truncateProbeTable() {
        jdbcTemplate.execute("TRUNCATE TABLE " + PROBE_TABLE);
    }

    private List<String> createVectorPool(int poolSize) {
        Random random = new Random(VECTOR_SEED);
        List<String> vectors = new ArrayList<>(poolSize);
        for (int index = 0; index < poolSize; index++) {
            vectors.add(normalizedVector(random));
        }
        return List.copyOf(vectors);
    }

    private String normalizedVector(Random random) {
        float[] values = new float[VECTOR_DIMENSION];
        double squaredNorm = 0.0;
        for (int index = 0; index < VECTOR_DIMENSION; index++) {
            values[index] = random.nextFloat() - 0.5F;
            squaredNorm += values[index] * values[index];
        }

        double norm = Math.sqrt(squaredNorm);
        StringBuilder vector = new StringBuilder(VECTOR_DIMENSION * 13).append('[');
        for (int index = 0; index < VECTOR_DIMENSION; index++) {
            if (index > 0) {
                vector.append(',');
            }
            vector.append(values[index] / norm);
        }
        return vector.append(']').toString();
    }

    private BenchmarkReport createReport(BenchmarkConfiguration configuration, List<ProfileResult> profiles)
        throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            return new BenchmarkReport(
                Instant.now().toString(),
                metadata.getDatabaseProductName(),
                metadata.getDatabaseProductVersion(),
                metadata.getDriverName(),
                metadata.getDriverVersion(),
                jdbcTemplate.queryForObject(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                    String.class
                ),
                System.getProperty("os.arch"),
                System.getProperty("java.version"),
                VECTOR_DIMENSION,
                HNSW_M,
                HNSW_EF_CONSTRUCTION,
                configuration.warmUpRows(),
                configuration.measuredRuns(),
                configuration.vectorPoolSize(),
                List.copyOf(profiles)
            );
        }
    }

    private void writeReport(Path outputPath, BenchmarkReport report) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), report);
        log.info("VECTOR_STORAGE_PERFORMANCE_REPORT path={}", outputPath.toAbsolutePath().normalize());
    }

    private long relationSize(String relationName) {
        return jdbcTemplate.queryForObject("SELECT pg_relation_size(?::regclass)", Long.class, relationName);
    }

    private long tableSize(String relationName) {
        return jdbcTemplate.queryForObject("SELECT pg_table_size(?::regclass)", Long.class, relationName);
    }

    private void logProfile(ProfileResult result) {
        log.info(
            "VECTOR_STORAGE_PERFORMANCE_RESULT rows={}, batchSize={}, durationP50Ms={}, "
                + "tpsP50={}, batchExecutions={}, tableBytes={}, indexBytes={}",
            result.rowCount(),
            result.batchSize(),
            result.durationMillis().p50(),
            result.rowsPerSecond().p50(),
            result.expectedBatchExecutions(),
            result.tableBytes(),
            result.indexBytes()
        );
    }

    static List<Integer> rotate(List<Integer> values, int offset) {
        List<Integer> rotated = new ArrayList<>(values);
        Collections.rotate(rotated, -(offset % values.size()));
        return List.copyOf(rotated);
    }

    static int expectedBatchExecutions(int rowCount, int batchSize) {
        if (rowCount <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("Row 수와 Batch Size는 1 이상이어야 합니다.");
        }
        return (int) (((long) rowCount + batchSize - 1L) / batchSize);
    }

    static double rowsPerSecond(int rowCount, double durationMillis) {
        if (rowCount <= 0 || !Double.isFinite(durationMillis) || durationMillis <= 0.0) {
            throw new IllegalArgumentException("Row 수와 저장 시간은 0보다 큰 유한값이어야 합니다.");
        }
        return rowCount / (durationMillis / 1_000.0);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }

    /** Benchmark 입력값을 검증하고 실행 중 불변으로 보존한다. */
    record BenchmarkConfiguration(
        List<Integer> rowCounts,
        List<Integer> batchSizes,
        int warmUpRows,
        int measuredRuns,
        int vectorPoolSize,
        Path outputPath
    ) {
        private static final String DEFAULT_SIZES = "1000,10000,100000";
        private static final String DEFAULT_BATCH_SIZES = "1,100,500,1000";
        private static final String DEFAULT_OUTPUT = "build/reports/vector-storage/vector-storage-latest.json";

        BenchmarkConfiguration {
            rowCounts = validatedPositiveValues(rowCounts, "저장 규모");
            batchSizes = validatedPositiveValues(batchSizes, "Batch Size");
            if (warmUpRows <= 0) {
                throw new IllegalArgumentException("Warm-up Row 수는 1 이상이어야 합니다.");
            }
            if (measuredRuns <= 0) {
                throw new IllegalArgumentException("본 측정 횟수는 1 이상이어야 합니다.");
            }
            if (vectorPoolSize <= 0) {
                throw new IllegalArgumentException("Vector Pool 크기는 1 이상이어야 합니다.");
            }
            if (outputPath == null || outputPath.toString().isBlank()) {
                throw new IllegalArgumentException("결과 경로는 비어 있을 수 없습니다.");
            }
        }

        static BenchmarkConfiguration fromSystemProperties() {
            return new BenchmarkConfiguration(
                parsePositiveValues(
                    System.getProperty("vector.storage.performance.sizes", DEFAULT_SIZES),
                    "저장 규모"
                ),
                parsePositiveValues(
                    System.getProperty("vector.storage.performance.batch-sizes", DEFAULT_BATCH_SIZES),
                    "Batch Size"
                ),
                positiveIntProperty("vector.storage.performance.warm-up-rows", 1_000),
                positiveIntProperty("vector.storage.performance.measured-runs", 2),
                positiveIntProperty("vector.storage.performance.vector-pool-size", 1_024),
                Path.of(System.getProperty("vector.storage.performance.output", DEFAULT_OUTPUT))
            );
        }

        static List<Integer> parsePositiveValues(String rawValue, String label) {
            try {
                return List.of(rawValue.split(",")).stream()
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .toList();
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(label + " 값은 쉼표로 구분한 정수여야 합니다.", exception);
            }
        }

        private static List<Integer> validatedPositiveValues(List<Integer> values, String label) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException(label + " 값은 한 개 이상이어야 합니다.");
            }
            if (values.stream().anyMatch(value -> value == null || value <= 0)) {
                throw new IllegalArgumentException(label + " 값은 1 이상의 정수여야 합니다.");
            }
            List<Integer> copied = List.copyOf(values);
            if (copied.stream().distinct().count() != copied.size()) {
                throw new IllegalArgumentException(label + " 값은 중복될 수 없습니다.");
            }
            return copied;
        }

        private static int positiveIntProperty(String name, int defaultValue) {
            try {
                int value = Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
                if (value <= 0) {
                    throw new IllegalArgumentException(name + "은 1 이상이어야 합니다.");
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + "은 정수여야 합니다.", exception);
            }
        }
    }

    /** 한 저장 Round의 시간, 처리량과 실제 Batch 실행 근거를 보존한다. */
    record RoundMeasurement(
        double durationMillis,
        double rowsPerSecond,
        int batchExecutions,
        int acknowledgedRows
    ) {
    }

    /** 반복 표본의 최솟값, 중앙값, p95와 최댓값을 보존한다. */
    record TimingSummary(int sampleCount, double minimum, double p50, double p95, double maximum) {
        static TimingSummary from(List<Double> values) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("측정 표본은 한 개 이상이어야 합니다.");
            }
            if (values.stream().anyMatch(value -> value == null || !Double.isFinite(value) || value <= 0.0)) {
                throw new IllegalArgumentException("측정 표본은 0보다 큰 유한값이어야 합니다.");
            }
            List<Double> sorted = values.stream().sorted().toList();
            return new TimingSummary(
                sorted.size(),
                sorted.get(0),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                sorted.get(sorted.size() - 1)
            );
        }

        private static double percentile(List<Double> sorted, double percentile) {
            int rank = Math.max(1, (int) Math.ceil(percentile * sorted.size()));
            return sorted.get(rank - 1);
        }
    }

    /** 저장 완료 뒤 Row 수와 Vector 차원 범위를 검증한다. */
    record StoredVectorSummary(long rowCount, int minDimensions, int maxDimensions) {
    }

    /** 한 저장 규모·Batch Size의 반복 결과와 저장 공간을 묶는다. */
    record ProfileResult(
        int rowCount,
        int batchSize,
        int executionOrder,
        int expectedBatchExecutions,
        List<RoundMeasurement> rounds,
        TimingSummary durationMillis,
        TimingSummary rowsPerSecond,
        long tableBytes,
        long indexBytes,
        long totalBytes
    ) {
    }

    /** 공개 가능한 실행 환경과 모든 저장 Profile을 JSON으로 표현한다. */
    record BenchmarkReport(
        String generatedAt,
        String databaseProduct,
        String databaseVersion,
        String jdbcDriver,
        String jdbcDriverVersion,
        String pgvectorVersion,
        String architecture,
        String javaVersion,
        int vectorDimension,
        int hnswM,
        int hnswEfConstruction,
        int warmUpRows,
        int measuredRuns,
        int vectorPoolSize,
        List<ProfileResult> profiles
    ) {
    }
}
