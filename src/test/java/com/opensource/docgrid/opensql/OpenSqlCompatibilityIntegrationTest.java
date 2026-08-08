package com.opensource.docgrid.opensql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import lombok.extern.slf4j.Slf4j;

/**
 * 공식 OpenSQL 17.8의 Flyway·pgvector·HNSW와 SKIP LOCKED PostgreSQL 호환 경계를 검증한다.
 *
 * <p>제품 Table은 Metadata만 확인하고 측정 Data는 전용 Test Schema의 Probe Table에 격리한다. Host,
 * Username과 Password는 수집하거나 출력하지 않으며 공개 가능한 Version·지연 집계만 Log로 남긴다.
 */
@Slf4j
@Tag("integration")
@Tag("opensql-verification")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("공식 OpenSQL 17.8 호환성·Vector 성능 통합 테스트")
class OpenSqlCompatibilityIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_opensql_compatibility_test";
    private static final String VECTOR_PROBE_TABLE = "opensql_vector_probe";
    private static final String QUEUE_PROBE_TABLE = "opensql_queue_probe";
    private static final String EXPECTED_SERVER_VERSION_PREFIX = "17.8";
    private static final String EXPECTED_PGVECTOR_VERSION = "0.8.1";
    private static final String EXPECTED_VECTOR_TYPE = "vector(1024)";
    private static final int VECTOR_DIMENSION = 1024;
    private static final int VECTOR_ROW_COUNT = 2_000;
    private static final int INSERT_BATCH_SIZE = 100;
    private static final int WARM_UP_QUERY_COUNT = 5;
    private static final int MEASURED_QUERY_COUNT = 30;
    private static final int TOP_K = 10;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-opensql-compatibility-test-secret-key-2026");
        registry.add(
            "spring.datasource.hikari.data-source-properties.ApplicationName",
            () -> "docgrid-opensql-compatibility-test"
        );
    }

    @AfterAll
    void dropIsolatedSchema() {
        if (Boolean.parseBoolean(System.getenv("KEEP_OPENSQL_COMPATIBILITY_SCHEMA"))) {
            log.warn("수동 진단을 위해 공식 OpenSQL 호환성 Test Schema를 유지합니다: {}", TEST_SCHEMA);
            return;
        }
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @Order(1)
    @DisplayName("Flyway 전체 적용 뒤 OpenSQL 17.8·pgvector 0.8.1·제품 HNSW 구조가 일치한다")
    void migration_createsExpectedOpenSqlVectorSchema() {
        String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        String serverVersion = jdbcTemplate.queryForObject("SHOW server_version", String.class);
        Integer serverVersionNumber = jdbcTemplate.queryForObject(
            "SELECT current_setting('server_version_num')::integer",
            Integer.class
        );
        String vectorVersion = jdbcTemplate.queryForObject(
            "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
            String.class
        );

        assertThat(currentSchema).isEqualTo(TEST_SCHEMA);
        assertThat(serverVersion).startsWith(EXPECTED_SERVER_VERSION_PREFIX);
        assertThat(serverVersionNumber).isBetween(170_000, 179_999);
        assertThat(vectorVersion).isEqualTo(EXPECTED_PGVECTOR_VERSION);
        assertThat(count(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '35' AND success = TRUE"
        )).isOne();
        assertThat(count(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE"
        )).isZero();

        assertThat(jdbcTemplate.queryForObject("""
            SELECT format_type(attribute.atttypid, attribute.atttypmod)
            FROM pg_attribute attribute
            JOIN pg_class table_definition ON table_definition.oid = attribute.attrelid
            JOIN pg_namespace namespace ON namespace.oid = table_definition.relnamespace
            WHERE namespace.nspname = current_schema()
              AND table_definition.relname = 'embeddings'
              AND attribute.attname = 'vector'
              AND attribute.attnum > 0
              AND NOT attribute.attisdropped
            """, String.class)).isEqualTo(EXPECTED_VECTOR_TYPE);
        assertThat(jdbcTemplate.queryForList("""
            SELECT indexdef
            FROM pg_indexes
            WHERE schemaname = current_schema()
              AND tablename = 'embeddings'
              AND indexdef ILIKE '%USING hnsw%'
              AND indexdef ILIKE '%vector_cosine_ops%'
            """, String.class)).hasSize(1);

        log.info(
            "OPENSQL_COMPATIBILITY_ENV serverVersion={}, serverVersionNumber={}, pgvector={}, "
                + "flywayVersion=35, vectorType={}",
            serverVersion,
            serverVersionNumber,
            vectorVersion,
            EXPECTED_VECTOR_TYPE
        );
    }

    @Test
    @Order(2)
    @Timeout(180)
    @DisplayName("2,000개 Vector에서 HNSW 실행 계획과 Cosine Top-K 지연을 측정한다")
    void vectorSearch_usesHnswAndReportsLatency() throws Exception {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + VECTOR_PROBE_TABLE);
        jdbcTemplate.execute(
            "CREATE TABLE " + VECTOR_PROBE_TABLE
                + " (id BIGSERIAL PRIMARY KEY, vector vector(1024) NOT NULL)"
        );
        List<String> vectors = deterministicVectors(VECTOR_ROW_COUNT, VECTOR_DIMENSION);

        try (Connection connection = dataSource.getConnection()) {
            // 1. 고정 Seed Vector를 Batch Insert해 실행마다 같은 검색 분포를 만든다.
            insertVectors(connection, vectors);

            // 2. 실제 Cosine HNSW Index를 구축하고 Planner Statistics를 갱신한다.
            execute(connection,
                "CREATE INDEX opensql_vector_probe_hnsw ON " + VECTOR_PROBE_TABLE
                    + " USING hnsw (vector vector_cosine_ops)"
            );
            execute(connection, "ANALYZE " + VECTOR_PROBE_TABLE);

            // 3. Seq Scan을 비활성화한 같은 Connection에서 HNSW 실행 계획을 확인한다.
            String queryVector = vectors.get(0);
            String plan = explainVectorSearch(connection, queryVector);
            assertThat(plan.toLowerCase(Locale.ROOT))
                .contains("index scan")
                .contains("opensql_vector_probe_hnsw");

            // 4. Warm-up을 분리하고 실제 Top-K 결과·거리 순서와 지연 분포를 수집한다.
            for (int index = 0; index < WARM_UP_QUERY_COUNT; index++) {
                queryVector(connection, queryVector);
            }
            List<Double> latenciesMillis = new ArrayList<>();
            for (int index = 0; index < MEASURED_QUERY_COUNT; index++) {
                long startedAt = System.nanoTime();
                List<VectorResult> results = queryVector(connection, queryVector);
                latenciesMillis.add(nanosToMillis(System.nanoTime() - startedAt));
                assertVectorResults(results);
            }

            VectorLatencySummary summary = VectorLatencySummary.from(latenciesMillis);
            log.info(
                "OPENSQL_VECTOR_RESULT rows={}, queries={}, topK={}, p50Ms={}, p95Ms={}, p99Ms={}, maxMs={}",
                VECTOR_ROW_COUNT,
                MEASURED_QUERY_COUNT,
                TOP_K,
                summary.p50Millis(),
                summary.p95Millis(),
                summary.p99Millis(),
                summary.maxMillis()
            );
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + VECTOR_PROBE_TABLE);
        }
    }

    @Test
    @Order(3)
    @Timeout(30)
    @DisplayName("잠긴 Queue Row를 SKIP LOCKED가 대기 없이 건너뛰고 해제 뒤 다시 조회한다")
    void skipLocked_skipsLockedRowWithoutWaiting() throws Exception {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + QUEUE_PROBE_TABLE);
        jdbcTemplate.execute(
            "CREATE TABLE " + QUEUE_PROBE_TABLE
                + " (id BIGSERIAL PRIMARY KEY, status VARCHAR(20) NOT NULL)"
        );
        jdbcTemplate.update("INSERT INTO " + QUEUE_PROBE_TABLE + " (status) VALUES ('PENDING')");

        try (
            Connection lockingConnection = dataSource.getConnection();
            Connection skippingConnection = dataSource.getConnection()
        ) {
            lockingConnection.setAutoCommit(false);
            skippingConnection.setAutoCommit(false);

            // 1. 첫 Connection이 유일한 Queue Row의 Lock을 Transaction 종료까지 보유한다.
            assertThat(selectQueueRow(lockingConnection, false)).isEqualTo(1L);

            // 2. 둘째 Connection은 같은 Row를 기다리지 않고 즉시 건너뛴다.
            long startedAt = System.nanoTime();
            assertThat(selectQueueRow(skippingConnection, true)).isNull();
            Duration skippedIn = Duration.ofNanos(System.nanoTime() - startedAt);
            assertThat(skippedIn).isLessThan(Duration.ofSeconds(5));

            // 3. 첫 Lock 해제와 둘째 Transaction 갱신 뒤 같은 Row를 정상 조회한다.
            lockingConnection.rollback();
            skippingConnection.rollback();
            assertThat(selectQueueRow(skippingConnection, true)).isEqualTo(1L);
            skippingConnection.rollback();

            log.info("OPENSQL_SKIP_LOCKED_RESULT skippedInMs={}, recovered=true", skippedIn.toMillis());
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + QUEUE_PROBE_TABLE);
        }
    }

    private void insertVectors(Connection connection, List<String> vectors) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + VECTOR_PROBE_TABLE + " (vector) VALUES (CAST(? AS vector))"
        )) {
            for (int index = 0; index < vectors.size(); index++) {
                statement.setString(1, vectors.get(index));
                statement.addBatch();
                if ((index + 1) % INSERT_BATCH_SIZE == 0) {
                    statement.executeBatch();
                }
            }
            if (vectors.size() % INSERT_BATCH_SIZE != 0) {
                statement.executeBatch();
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String explainVectorSearch(Connection connection, String queryVector) throws SQLException {
        execute(connection, "SET enable_seqscan = off");
        try (PreparedStatement statement = connection.prepareStatement(
            "EXPLAIN (COSTS OFF) SELECT id FROM " + VECTOR_PROBE_TABLE
                + " ORDER BY vector <=> CAST(? AS vector) LIMIT " + TOP_K
        )) {
            statement.setString(1, queryVector);
            try (ResultSet resultSet = statement.executeQuery()) {
                StringBuilder plan = new StringBuilder();
                while (resultSet.next()) {
                    plan.append(resultSet.getString(1)).append('\n');
                }
                return plan.toString();
            }
        } finally {
            execute(connection, "RESET enable_seqscan");
        }
    }

    private List<VectorResult> queryVector(Connection connection, String queryVector) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, vector <=> CAST(? AS vector) AS distance FROM " + VECTOR_PROBE_TABLE
                + " ORDER BY vector <=> CAST(? AS vector) LIMIT " + TOP_K
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

    private void assertVectorResults(List<VectorResult> results) {
        assertThat(results).hasSize(TOP_K);
        assertThat(results.get(0).id()).isEqualTo(1L);
        assertThat(results.get(0).distance()).isEqualTo(0.0);
        assertThat(results).allMatch(result -> Double.isFinite(result.distance()));
        assertThat(results).isSortedAccordingTo(Comparator.comparingDouble(VectorResult::distance));
    }

    private Long selectQueueRow(Connection connection, boolean skipLocked) throws SQLException {
        String suffix = skipLocked ? " SKIP LOCKED" : "";
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM " + QUEUE_PROBE_TABLE
                + " WHERE status = 'PENDING' ORDER BY id FOR UPDATE" + suffix
        )) {
            statement.setQueryTimeout(5);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private List<String> deterministicVectors(int count, int dimension) {
        Random random = new Random(124L);
        List<String> vectors = new ArrayList<>(count);
        for (int row = 0; row < count; row++) {
            float[] values = new float[dimension];
            double squaredNorm = 0.0;
            for (int index = 0; index < dimension; index++) {
                values[index] = random.nextFloat() - 0.5F;
                squaredNorm += values[index] * values[index];
            }
            double norm = Math.sqrt(squaredNorm);
            StringBuilder vector = new StringBuilder(dimension * 12).append('[');
            for (int index = 0; index < dimension; index++) {
                if (index > 0) {
                    vector.append(',');
                }
                vector.append(values[index] / norm);
            }
            vector.append(']');
            vectors.add(vector.toString());
        }
        return List.copyOf(vectors);
    }

    private double nanosToMillis(long nanos) {
        return nanos / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    /** Top-K 한 행의 식별자와 Cosine Distance를 보존한다. */
    private record VectorResult(long id, double distance) {
    }

    /** Hardware 의존 임계값 없이 공개 가능한 Vector 검색 지연 Percentile만 집계한다. */
    private record VectorLatencySummary(
        double p50Millis,
        double p95Millis,
        double p99Millis,
        double maxMillis
    ) {
        private static VectorLatencySummary from(List<Double> values) {
            List<Double> sorted = values.stream().sorted().toList();
            return new VectorLatencySummary(
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
}
