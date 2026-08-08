package com.opensource.docgrid.domain.search.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL pgvector의 Exact Seq Scan과 HNSW Index Scan을 같은 Vector·Query로 비교한다.
 *
 * <p>실제 성능 측정은 일반 회귀 테스트에서 제외하며, 전용 Test Schema와 Probe Table만 사용한다.
 * 절대 지연을 합격 기준으로 삼지 않고 실행 계획, Top-K 계약과 Recall 계산의 재현성을 검증한다.
 */
@Slf4j
@Tag("integration")
@Tag("vector-search-performance")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Exact Seq Scan·HNSW 규모별 Vector 검색 Benchmark")
class VectorSearchPerformanceBenchmark {

    static final int VECTOR_DIMENSION = 1024;
    static final int HNSW_M = 16;
    static final int HNSW_EF_CONSTRUCTION = 64;
    static final int HNSW_EF_SEARCH = 40;
    private static final long VECTOR_SEED = 131L;
    private static final int INSERT_BATCH_SIZE = 100;
    private static final String TEST_SCHEMA = "docgrid_vector_search_performance_test_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String PROBE_TABLE = "vector_search_performance_probe";
    private static final String HNSW_INDEX = "vector_search_performance_hnsw";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-vector-search-performance-test-secret-key-2026");
        registry.add(
            "spring.datasource.hikari.data-source-properties.ApplicationName",
            () -> "docgrid-vector-search-performance-test"
        );
    }

    @AfterAll
    void dropIsolatedSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @Timeout(1_800)
    @DisplayName("데이터 규모별 Exact 기준선과 HNSW 지연·Recall·저장 비용을 비교한다")
    void compareExactAndHnswByDataScale() throws Exception {
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        List<ProfileResult> profiles = new ArrayList<>();

        for (int rowCount : configuration.rowCounts()) {
            profiles.add(runProfile(rowCount, configuration));
        }

        BenchmarkReport report = new BenchmarkReport(
            Instant.now().toString(),
            jdbcTemplate.queryForObject("SHOW server_version", String.class),
            jdbcTemplate.queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                String.class
            ),
            System.getProperty("os.arch"),
            System.getProperty("java.version"),
            VECTOR_DIMENSION,
            configuration.topK(),
            HNSW_M,
            HNSW_EF_CONSTRUCTION,
            HNSW_EF_SEARCH,
            List.copyOf(profiles)
        );

        writeReport(configuration.outputPath(), report);
        assertThat(report.profiles()).extracting(ProfileResult::rowCount)
            .containsExactlyElementsOf(configuration.rowCounts());
    }

    private ProfileResult runProfile(int rowCount, BenchmarkConfiguration configuration) throws Exception {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        jdbcTemplate.execute(
            "CREATE TABLE " + PROBE_TABLE
                + " (id BIGSERIAL PRIMARY KEY, vector vector(1024) NOT NULL)"
        );

        try (Connection connection = dataSource.getConnection()) {
            // 1. 같은 Seed Sequence의 Prefix를 적재해 규모가 달라도 입력 분포를 유지한다.
            insertDeterministicVectors(connection, rowCount);
            execute(connection, "ANALYZE " + PROBE_TABLE);

            List<Long> queryIds = selectQueryIds(rowCount, configuration.queryCount());
            Map<Long, String> queryVectors = loadQueryVectors(connection, queryIds);

            // 2. Index 생성 전 Exact Seq Scan을 정답 집합과 지연 기준선으로 측정한다.
            PlanSummary exactPlan = explainSearch(
                connection,
                queryVectors.get(queryIds.get(0)),
                configuration.topK(),
                SearchMode.EXACT
            );
            assertThat(exactPlan.nodeTypes()).contains("Seq Scan");
            assertThat(exactPlan.indexNames()).doesNotContain(HNSW_INDEX);
            SearchMeasurement exact = measureSearch(
                connection,
                queryIds,
                queryVectors,
                configuration,
                SearchMode.EXACT
            );

            // 3. 기본 HNSW Parameter를 명시해 실행 환경별 암묵적 설정 차이를 제거한다.
            long indexStartedAt = System.nanoTime();
            execute(
                connection,
                "CREATE INDEX " + HNSW_INDEX + " ON " + PROBE_TABLE
                    + " USING hnsw (vector vector_cosine_ops) WITH (m = " + HNSW_M
                    + ", ef_construction = " + HNSW_EF_CONSTRUCTION + ")"
            );
            double indexBuildMillis = nanosToMillis(System.nanoTime() - indexStartedAt);
            execute(connection, "ANALYZE " + PROBE_TABLE);

            // 4. 동일 Query를 HNSW로 실행하고 Exact Top-K와의 Recall을 계산한다.
            PlanSummary hnswPlan = explainSearch(
                connection,
                queryVectors.get(queryIds.get(0)),
                configuration.topK(),
                SearchMode.HNSW
            );
            assertThat(hnswPlan.nodeTypes()).contains("Index Scan");
            assertThat(hnswPlan.indexNames()).contains(HNSW_INDEX);
            SearchMeasurement hnsw = measureSearch(
                connection,
                queryIds,
                queryVectors,
                configuration,
                SearchMode.HNSW
            );
            RecallSummary recall = recallAtK(exact.resultsByQuery(), hnsw.resultsByQuery(), configuration.topK());

            ProfileResult result = new ProfileResult(
                rowCount,
                configuration.queryCount(),
                configuration.warmUpRuns(),
                configuration.measuredRuns(),
                new SearchMetrics(exactPlan, exact.timing()),
                new SearchMetrics(hnswPlan, hnsw.timing()),
                recall.average(),
                recall.minimum(),
                indexBuildMillis,
                relationSize(PROBE_TABLE),
                relationSize(HNSW_INDEX),
                totalRelationSize(PROBE_TABLE)
            );
            logProfile(result);
            return result;
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        }
    }

    private void insertDeterministicVectors(Connection connection, int rowCount) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Random random = new Random(VECTOR_SEED);
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + PROBE_TABLE + " (vector) VALUES (CAST(? AS vector))"
        )) {
            for (int row = 0; row < rowCount; row++) {
                statement.setString(1, normalizedVector(random));
                statement.addBatch();
                if ((row + 1) % INSERT_BATCH_SIZE == 0) {
                    statement.executeBatch();
                }
            }
            if (rowCount % INSERT_BATCH_SIZE != 0) {
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
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

    private List<Long> selectQueryIds(int rowCount, int queryCount) {
        if (queryCount == 1) {
            return List.of(1L);
        }
        List<Long> queryIds = new ArrayList<>(queryCount);
        for (int index = 0; index < queryCount; index++) {
            long id = 1L + Math.round(index * (rowCount - 1.0) / (queryCount - 1.0));
            queryIds.add(id);
        }
        return List.copyOf(queryIds);
    }

    private Map<Long, String> loadQueryVectors(Connection connection, List<Long> queryIds) throws SQLException {
        Map<Long, String> vectors = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT vector::text FROM " + PROBE_TABLE + " WHERE id = ?"
        )) {
            for (long queryId : queryIds) {
                statement.setLong(1, queryId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    vectors.put(queryId, resultSet.getString(1));
                }
            }
        }
        return Map.copyOf(vectors);
    }

    private PlanSummary explainSearch(
        Connection connection,
        String queryVector,
        int topK,
        SearchMode mode
    ) throws SQLException, IOException {
        configurePlanner(connection, mode);
        try (PreparedStatement statement = connection.prepareStatement(
            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT id FROM " + PROBE_TABLE
                + " ORDER BY vector <=> CAST(? AS vector) LIMIT " + topK
        )) {
            statement.setString(1, queryVector);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                String rawJson = resultSet.getString(1);
                JsonNode explain = objectMapper.readTree(rawJson).get(0);
                JsonNode plan = explain.path("Plan");
                List<String> nodeTypes = new ArrayList<>();
                List<String> indexNames = new ArrayList<>();
                collectPlanDetails(plan, nodeTypes, indexNames);
                return new PlanSummary(
                    List.copyOf(nodeTypes),
                    List.copyOf(indexNames),
                    explain.path("Planning Time").asDouble(),
                    explain.path("Execution Time").asDouble(),
                    plan.path("Shared Hit Blocks").asLong(),
                    plan.path("Shared Read Blocks").asLong(),
                    rawJson
                );
            }
        } finally {
            resetPlanner(connection);
        }
    }

    private SearchMeasurement measureSearch(
        Connection connection,
        List<Long> queryIds,
        Map<Long, String> queryVectors,
        BenchmarkConfiguration configuration,
        SearchMode mode
    ) throws SQLException {
        configurePlanner(connection, mode);
        try {
            // 1. 실행 계획 수집과 별도로 Warm-up을 수행하고 측정 표본에서는 제외한다.
            for (int run = 0; run < configuration.warmUpRuns(); run++) {
                for (long queryId : queryIds) {
                    assertSearchResults(
                        queryVector(connection, queryVectors.get(queryId), configuration.topK()),
                        configuration.topK()
                    );
                }
            }

            // 2. 같은 Query 순서를 반복하고 첫 측정 결과를 Recall 비교용으로 보존한다.
            List<Double> latenciesMillis = new ArrayList<>();
            Map<Long, List<VectorResult>> resultsByQuery = new LinkedHashMap<>();
            for (int run = 0; run < configuration.measuredRuns(); run++) {
                for (long queryId : queryIds) {
                    long startedAt = System.nanoTime();
                    List<VectorResult> results = queryVector(
                        connection,
                        queryVectors.get(queryId),
                        configuration.topK()
                    );
                    latenciesMillis.add(nanosToMillis(System.nanoTime() - startedAt));
                    assertSearchResults(results, configuration.topK());
                    if (mode == SearchMode.EXACT) {
                        assertThat(results.get(0).id()).isEqualTo(queryId);
                        assertThat(results.get(0).distance()).isEqualTo(0.0);
                    }
                    if (run == 0) {
                        resultsByQuery.put(queryId, results);
                    }
                }
            }
            return new SearchMeasurement(
                TimingSummary.from(latenciesMillis),
                Map.copyOf(resultsByQuery)
            );
        } finally {
            resetPlanner(connection);
        }
    }

    private List<VectorResult> queryVector(Connection connection, String queryVector, int topK)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, vector <=> CAST(? AS vector) AS distance FROM " + PROBE_TABLE
                + " ORDER BY vector <=> CAST(? AS vector) LIMIT " + topK
        )) {
            statement.setString(1, queryVector);
            statement.setString(2, queryVector);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<VectorResult> results = new ArrayList<>();
                while (resultSet.next()) {
                    results.add(new VectorResult(resultSet.getLong("id"), resultSet.getDouble("distance")));
                }
                return List.copyOf(results);
            }
        }
    }

    private void configurePlanner(Connection connection, SearchMode mode) throws SQLException {
        if (mode == SearchMode.EXACT) {
            execute(connection, "SET enable_indexscan = off");
            execute(connection, "SET enable_bitmapscan = off");
            execute(connection, "SET enable_seqscan = on");
            return;
        }
        execute(connection, "SET enable_indexscan = on");
        execute(connection, "SET enable_bitmapscan = on");
        execute(connection, "SET enable_seqscan = off");
        execute(connection, "SET hnsw.ef_search = " + HNSW_EF_SEARCH);
    }

    private void resetPlanner(Connection connection) throws SQLException {
        execute(connection, "RESET enable_indexscan");
        execute(connection, "RESET enable_bitmapscan");
        execute(connection, "RESET enable_seqscan");
        execute(connection, "RESET hnsw.ef_search");
    }

    private void collectPlanDetails(JsonNode plan, List<String> nodeTypes, List<String> indexNames) {
        nodeTypes.add(plan.path("Node Type").asText());
        if (plan.has("Index Name")) {
            indexNames.add(plan.path("Index Name").asText());
        }
        plan.path("Plans").forEach(child -> collectPlanDetails(child, nodeTypes, indexNames));
    }

    private void assertSearchResults(List<VectorResult> results, int topK) {
        assertThat(results).hasSize(topK);
        assertThat(results).allMatch(result -> Double.isFinite(result.distance()));
        assertThat(results).isSortedAccordingTo(Comparator.comparingDouble(VectorResult::distance));
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long relationSize(String relationName) {
        return jdbcTemplate.queryForObject("SELECT pg_relation_size(?::regclass)", Long.class, relationName);
    }

    private long totalRelationSize(String relationName) {
        return jdbcTemplate.queryForObject("SELECT pg_total_relation_size(?::regclass)", Long.class, relationName);
    }

    private void writeReport(Path outputPath, BenchmarkReport report) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), report);
        log.info("VECTOR_SEARCH_PERFORMANCE_REPORT path={}", outputPath.toAbsolutePath().normalize());
    }

    private void logProfile(ProfileResult result) {
        log.info(
            "VECTOR_SEARCH_PERFORMANCE_RESULT rows={}, exactP50Ms={}, exactP95Ms={}, "
                + "hnswP50Ms={}, hnswP95Ms={}, averageRecallAtK={}, minimumRecallAtK={}, "
                + "indexBuildMs={}, tableBytes={}, indexBytes={}, totalRelationBytes={}",
            result.rowCount(),
            result.exact().timing().p50Millis(),
            result.exact().timing().p95Millis(),
            result.hnsw().timing().p50Millis(),
            result.hnsw().timing().p95Millis(),
            result.averageRecallAtK(),
            result.minimumRecallAtK(),
            result.indexBuildMillis(),
            result.tableBytes(),
            result.indexBytes(),
            result.totalRelationBytes()
        );
    }

    static RecallSummary recallAtK(
        Map<Long, List<VectorResult>> exactResults,
        Map<Long, List<VectorResult>> hnswResults,
        int topK
    ) {
        assertThat(hnswResults.keySet()).containsExactlyInAnyOrderElementsOf(exactResults.keySet());
        List<Double> recalls = new ArrayList<>();
        for (Map.Entry<Long, List<VectorResult>> entry : exactResults.entrySet()) {
            Set<Long> exactIds = entry.getValue().stream()
                .limit(topK)
                .map(VectorResult::id)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
            long overlap = hnswResults.get(entry.getKey()).stream()
                .limit(topK)
                .map(VectorResult::id)
                .filter(exactIds::contains)
                .count();
            recalls.add(overlap / (double) topK);
        }
        return new RecallSummary(
            recalls.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
            recalls.stream().mapToDouble(Double::doubleValue).min().orElseThrow()
        );
    }

    private static double nanosToMillis(long nanos) {
        return nanos / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }

    /** 검색 경로별 Planner 강제 설정을 구분한다. */
    private enum SearchMode {
        EXACT,
        HNSW
    }

    /** Benchmark 입력값을 검증하고 실행 중 불변으로 보존한다. */
    record BenchmarkConfiguration(
        List<Integer> rowCounts,
        int queryCount,
        int warmUpRuns,
        int measuredRuns,
        int topK,
        Path outputPath
    ) {
        private static final String DEFAULT_SIZES = "2000,10000,50000";
        private static final String DEFAULT_OUTPUT = "build/reports/vector-search/exact-vs-hnsw.json";

        BenchmarkConfiguration {
            rowCounts = List.copyOf(rowCounts);
            assertThat(rowCounts).isNotEmpty().doesNotHaveDuplicates().isSorted();
            assertThat(rowCounts).allMatch(size -> size >= topK);
            assertThat(queryCount).isPositive().isLessThanOrEqualTo(rowCounts.get(0));
            assertThat(warmUpRuns).isNotNegative();
            assertThat(measuredRuns).isPositive();
            assertThat(topK).isPositive();
            assertThat(outputPath.toString()).isNotBlank();
        }

        static BenchmarkConfiguration fromSystemProperties() {
            int topK = positiveIntProperty("vector.search.performance.top-k", 10);
            return new BenchmarkConfiguration(
                parseRowCounts(System.getProperty("vector.search.performance.sizes", DEFAULT_SIZES)),
                positiveIntProperty("vector.search.performance.query-count", 10),
                nonNegativeIntProperty("vector.search.performance.warm-up", 3),
                positiveIntProperty("vector.search.performance.measured-runs", 10),
                topK,
                Path.of(System.getProperty("vector.search.performance.output", DEFAULT_OUTPUT))
            );
        }

        static List<Integer> parseRowCounts(String rawValue) {
            try {
                return List.of(rawValue.split(",")).stream()
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .toList();
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("데이터 규모는 쉼표로 구분한 정수여야 합니다.", exception);
            }
        }

        private static int positiveIntProperty(String name, int defaultValue) {
            int value = integerProperty(name, defaultValue);
            if (value <= 0) {
                throw new IllegalArgumentException(name + "은 1 이상이어야 합니다.");
            }
            return value;
        }

        private static int nonNegativeIntProperty(String name, int defaultValue) {
            int value = integerProperty(name, defaultValue);
            if (value < 0) {
                throw new IllegalArgumentException(name + "은 0 이상이어야 합니다.");
            }
            return value;
        }

        private static int integerProperty(String name, int defaultValue) {
            try {
                return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + "은 정수여야 합니다.", exception);
            }
        }
    }

    /** 한 Query의 Vector 식별자와 Cosine Distance를 보존한다. */
    record VectorResult(long id, double distance) {
    }

    /** 실행 계획의 경로, 사용 Index, 시간과 Buffer 근거를 보존한다. */
    record PlanSummary(
        List<String> nodeTypes,
        List<String> indexNames,
        double planningMillis,
        double executionMillis,
        long sharedHitBlocks,
        long sharedReadBlocks,
        String rawJson
    ) {
    }

    /** Warm-up을 제외한 검색 지연 표본의 Percentile을 보존한다. */
    record TimingSummary(
        int sampleCount,
        double p50Millis,
        double p95Millis,
        double p99Millis,
        double maxMillis
    ) {
        static TimingSummary from(List<Double> values) {
            List<Double> sorted = values.stream().sorted().toList();
            assertThat(sorted).isNotEmpty();
            return new TimingSummary(
                sorted.size(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99),
                sorted.get(sorted.size() - 1)
            );
        }

        private static double percentile(List<Double> sorted, double percentile) {
            int rank = Math.max(1, (int) Math.ceil(percentile * sorted.size()));
            return sorted.get(rank - 1);
        }
    }

    /** 검색 경로 하나의 실행 계획과 지연 분포를 묶는다. */
    record SearchMetrics(PlanSummary plan, TimingSummary timing) {
    }

    /** 검색 경로 하나의 지연과 Query별 Top-K를 다음 비교 단계로 전달한다. */
    private record SearchMeasurement(
        TimingSummary timing,
        Map<Long, List<VectorResult>> resultsByQuery
    ) {
    }

    /** Exact Top-K 대비 HNSW Recall의 평균과 최솟값을 보존한다. */
    record RecallSummary(double average, double minimum) {
        RecallSummary {
            assertThat(average).isBetween(0.0, 1.0);
            assertThat(minimum).isBetween(0.0, 1.0);
        }
    }

    /** 한 데이터 규모에서 측정한 검색·Recall·저장 비용을 보존한다. */
    record ProfileResult(
        int rowCount,
        int queryCount,
        int warmUpRuns,
        int measuredRuns,
        SearchMetrics exact,
        SearchMetrics hnsw,
        double averageRecallAtK,
        double minimumRecallAtK,
        double indexBuildMillis,
        long tableBytes,
        long indexBytes,
        long totalRelationBytes
    ) {
    }

    /** 공개 가능한 실행 환경과 모든 데이터 규모의 측정 결과를 JSON으로 표현한다. */
    record BenchmarkReport(
        String generatedAt,
        String serverVersion,
        String pgvectorVersion,
        String architecture,
        String javaVersion,
        int dimension,
        int topK,
        int hnswM,
        int hnswEfConstruction,
        int hnswEfSearch,
        List<ProfileResult> profiles
    ) {
    }
}
