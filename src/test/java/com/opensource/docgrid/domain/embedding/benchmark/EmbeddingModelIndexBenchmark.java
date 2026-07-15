package com.opensource.docgrid.domain.embedding.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Tag("benchmark")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("EmbeddingModel Partial Index 조회 Benchmark")
class EmbeddingModelIndexBenchmark {

    private static final String TEST_SCHEMA = "docgrid_embedding_benchmark_test";
    private static final String BENCHMARK_TABLE = "embedding_models_index_benchmark";
    private static final String PARTIAL_INDEX = "uk_benchmark_one_active_searchable";
    private static final int DATA_SIZE = 100_000;
    private static final int WARM_UP_RUNS = 5;
    private static final int MEASURED_RUNS = 20;

    private static final String LOOKUP_SQL = """
        SELECT id, model_name
        FROM embedding_models_index_benchmark
        WHERE is_active = TRUE
          AND is_searchable = TRUE
        """;

    private static final String EXPLAIN_SQL = """
        EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
        SELECT id, model_name
        FROM embedding_models_index_benchmark
        WHERE is_active = TRUE
          AND is_searchable = TRUE
        """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void useIsolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
    }

    @AfterAll
    void cleanUpSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("Partial Unique Index 적용 전후 조회 계획과 반복 측정값을 수집한다")
    void compareLookupPlanBeforeAndAfterPartialIndex() throws JsonProcessingException {
        prepareBenchmarkTable();

        TimingSummary beforeTiming = measureLookup();
        PlanSummary beforePlan = explainLookup();
        long tableSizeBytes = relationSize(BENCHMARK_TABLE);
        long indexesBeforeBytes = totalIndexSize(BENCHMARK_TABLE);

        jdbcTemplate.execute("""
            CREATE UNIQUE INDEX uk_benchmark_one_active_searchable
                ON embedding_models_index_benchmark ((1))
                WHERE is_active = TRUE
                  AND is_searchable = TRUE
            """);
        jdbcTemplate.execute("ANALYZE " + BENCHMARK_TABLE);

        TimingSummary afterTiming = measureLookup();
        PlanSummary afterPlan = explainLookup();
        long partialIndexSizeBytes = relationSize(PARTIAL_INDEX);
        long indexesAfterBytes = totalIndexSize(BENCHMARK_TABLE);

        assertThat(countDefaultModels()).isEqualTo(1);
        assertThat(beforePlan.indexNames()).doesNotContain(PARTIAL_INDEX);
        assertThat(afterPlan.indexNames()).contains(PARTIAL_INDEX);

        double medianChangePercent =
            ((beforeTiming.medianMillis() - afterTiming.medianMillis()) / beforeTiming.medianMillis()) * 100.0;

        System.out.printf(Locale.ROOT, """
            BENCHMARK_RESULT dataSize=%d warmUp=%d measuredRuns=%d
            BEFORE_TIMING medianMs=%.6f minMs=%.6f maxMs=%.6f
            AFTER_TIMING medianMs=%.6f minMs=%.6f maxMs=%.6f
            MEDIAN_CHANGE_PERCENT=%.3f
            BEFORE_PLAN nodes=%s indexes=%s planningMs=%.6f executionMs=%.6f actualRows=%d sharedHitBlocks=%d sharedReadBlocks=%d
            AFTER_PLAN nodes=%s indexes=%s planningMs=%.6f executionMs=%.6f actualRows=%d sharedHitBlocks=%d sharedReadBlocks=%d
            RELATION_SIZES tableBytes=%d indexesBeforeBytes=%d indexesAfterBytes=%d partialIndexBytes=%d
            BEFORE_EXPLAIN_JSON=%s
            AFTER_EXPLAIN_JSON=%s
            """,
            DATA_SIZE,
            WARM_UP_RUNS,
            MEASURED_RUNS,
            beforeTiming.medianMillis(),
            beforeTiming.minMillis(),
            beforeTiming.maxMillis(),
            afterTiming.medianMillis(),
            afterTiming.minMillis(),
            afterTiming.maxMillis(),
            medianChangePercent,
            beforePlan.nodeTypes(),
            beforePlan.indexNames(),
            beforePlan.planningTimeMillis(),
            beforePlan.executionTimeMillis(),
            beforePlan.actualRows(),
            beforePlan.sharedHitBlocks(),
            beforePlan.sharedReadBlocks(),
            afterPlan.nodeTypes(),
            afterPlan.indexNames(),
            afterPlan.planningTimeMillis(),
            afterPlan.executionTimeMillis(),
            afterPlan.actualRows(),
            afterPlan.sharedHitBlocks(),
            afterPlan.sharedReadBlocks(),
            tableSizeBytes,
            indexesBeforeBytes,
            indexesAfterBytes,
            partialIndexSizeBytes,
            beforePlan.rawJson(),
            afterPlan.rawJson()
        );
    }

    private void prepareBenchmarkTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + BENCHMARK_TABLE);
        jdbcTemplate.execute("""
            CREATE TABLE embedding_models_index_benchmark (
                id BIGSERIAL PRIMARY KEY,
                model_name VARCHAR(200) NOT NULL,
                is_active BOOLEAN NOT NULL,
                is_searchable BOOLEAN NOT NULL
            )
            """);
        jdbcTemplate.execute("""
            CREATE INDEX idx_benchmark_is_active
                ON embedding_models_index_benchmark (is_active)
            """);
        jdbcTemplate.execute("""
            CREATE INDEX idx_benchmark_is_searchable
                ON embedding_models_index_benchmark (is_searchable)
            """);
        jdbcTemplate.update("""
            INSERT INTO embedding_models_index_benchmark (model_name, is_active, is_searchable)
            SELECT 'benchmark-model-' || sequence,
                   CASE
                       WHEN sequence = 1 THEN TRUE
                       WHEN sequence % 3 = 0 THEN TRUE
                       ELSE FALSE
                   END,
                   CASE
                       WHEN sequence = 1 THEN TRUE
                       WHEN sequence % 3 = 0 THEN FALSE
                       WHEN sequence % 5 = 0 THEN TRUE
                       ELSE FALSE
                   END
            FROM generate_series(1, ?) AS sequence
            """, DATA_SIZE);
        jdbcTemplate.execute("ANALYZE " + BENCHMARK_TABLE);
    }

    private TimingSummary measureLookup() {
        for (int index = 0; index < WARM_UP_RUNS; index++) {
            assertThat(jdbcTemplate.queryForList(LOOKUP_SQL)).hasSize(1);
        }

        List<Double> measurements = new ArrayList<>();
        for (int index = 0; index < MEASURED_RUNS; index++) {
            long startedAt = System.nanoTime();
            List<Map<String, Object>> result = jdbcTemplate.queryForList(LOOKUP_SQL);
            measurements.add((System.nanoTime() - startedAt) / 1_000_000.0);
            assertThat(result).hasSize(1);
        }

        Collections.sort(measurements);
        double median = (measurements.get(MEASURED_RUNS / 2 - 1) + measurements.get(MEASURED_RUNS / 2)) / 2.0;
        return new TimingSummary(
            median,
            measurements.get(0),
            measurements.get(measurements.size() - 1)
        );
    }

    private PlanSummary explainLookup() throws JsonProcessingException {
        String rawJson = jdbcTemplate.queryForObject(EXPLAIN_SQL, String.class);
        JsonNode explain = objectMapper.readTree(rawJson).get(0);
        JsonNode plan = explain.get("Plan");

        List<String> nodeTypes = new ArrayList<>();
        List<String> indexNames = new ArrayList<>();
        collectPlanDetails(plan, nodeTypes, indexNames);

        return new PlanSummary(
            nodeTypes,
            indexNames,
            explain.path("Planning Time").asDouble(),
            explain.path("Execution Time").asDouble(),
            plan.path("Actual Rows").asLong(),
            plan.path("Shared Hit Blocks").asLong(),
            plan.path("Shared Read Blocks").asLong(),
            rawJson
        );
    }

    private void collectPlanDetails(JsonNode plan, List<String> nodeTypes, List<String> indexNames) {
        nodeTypes.add(plan.path("Node Type").asText());
        if (plan.has("Index Name")) {
            indexNames.add(plan.get("Index Name").asText());
        }
        plan.path("Plans").forEach(child -> collectPlanDetails(child, nodeTypes, indexNames));
    }

    private int countDefaultModels() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + BENCHMARK_TABLE + " WHERE is_active = TRUE AND is_searchable = TRUE",
            Integer.class
        );
    }

    private long relationSize(String relationName) {
        return jdbcTemplate.queryForObject(
            "SELECT pg_relation_size(?::regclass)",
            Long.class,
            relationName
        );
    }

    private long totalIndexSize(String tableName) {
        return jdbcTemplate.queryForObject(
            "SELECT pg_indexes_size(?::regclass)",
            Long.class,
            tableName
        );
    }

    private record TimingSummary(double medianMillis, double minMillis, double maxMillis) {
    }

    private record PlanSummary(
        List<String> nodeTypes,
        List<String> indexNames,
        double planningTimeMillis,
        double executionTimeMillis,
        long actualRows,
        long sharedHitBlocks,
        long sharedReadBlocks,
        String rawJson
    ) {
    }
}
