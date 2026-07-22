package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기본 Embedding Model 단일성 제약과 동시 저장 경쟁을 실제 OpenSQL에서 검증하는 통합 테스트.
 *
 * <p>실제 bge-m3 Seed가 생성한 Chunk와 Embedding의 FK를 안전한 순서로 정리한 뒤 모델 제약 자체를
 * 독립적으로 측정한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("기본 임베딩 모델 DB 제약 통합 테스트")
class EmbeddingModelConstraintIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_embedding_constraint_test";
    private static final String MODEL_TABLE = "embedding_models";
    private static final String RACE_TABLE = "embedding_models_race_without_constraint";
    private static final String INDEX_NAME = "uk_embedding_models_one_active_searchable";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final int CONCURRENCY_REPETITIONS = 20;
    private static final long TIMEOUT_SECONDS = 10;
    private static final DateTimeFormatter TRACE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final String INSERT_MODEL_SQL = """
        INSERT INTO embedding_models (
            provider,
            model_name,
            model_version,
            dimension,
            distance_metric,
            is_active,
            is_searchable,
            vector_storage_strategy,
            config_json,
            created_at,
            updated_at
        )
        VALUES ('MOCK', ?, 'v1', 1024, 'COSINE', ?, ?, 'SINGLE_DIMENSION', NULL,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executorService;
    private final AtomicInteger workerSequence = new AtomicInteger();
    private final AtomicInteger traceSequence = new AtomicInteger();
    private final Object traceMonitor = new Object();

    @DynamicPropertySource
    static void useIsolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
    }

    @BeforeAll
    void createExecutor() {
        executorService = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("embedding-worker-" + workerSequence.incrementAndGet());
            return thread;
        });
    }

    @BeforeEach
    void resetModelState() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + RACE_TABLE);
        // Seed 데이터의 FK가 embedding_models 삭제를 막으므로 자식 테이블부터 역순으로 정리한다.
        jdbcTemplate.update("DELETE FROM embeddings");
        jdbcTemplate.update("DELETE FROM document_chunks");
        jdbcTemplate.update("DELETE FROM " + MODEL_TABLE);
    }

    @AfterAll
    void cleanUpSchemaAndExecutor() throws InterruptedException {
        executorService.shutdownNow();
        assertThat(executorService.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("V27의 Partial Unique Index가 UNIQUE와 true+true Predicate로 생성된다")
    void partialUniqueIndex_isCreatedWithExpectedDefinition() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
            SELECT index_class.relname AS index_name,
                   table_class.relname AS table_name,
                   i.indisunique AS is_unique,
                   pg_get_expr(i.indpred, i.indrelid) AS predicate,
                   pg_get_indexdef(i.indexrelid) AS index_definition
            FROM pg_index i
            JOIN pg_class index_class ON index_class.oid = i.indexrelid
            JOIN pg_class table_class ON table_class.oid = i.indrelid
            JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
            WHERE namespace.nspname = current_schema()
              AND table_class.relname = 'embedding_models'
              AND index_class.relname = 'uk_embedding_models_one_active_searchable'
            """);

        assertThat(indexes).hasSize(1);
        Map<String, Object> index = indexes.get(0);
        String predicate = index.get("predicate").toString().toLowerCase();
        String definition = index.get("index_definition").toString().toLowerCase();

        assertThat(index.get("index_name")).isEqualTo(INDEX_NAME);
        assertThat(index.get("table_name")).isEqualTo(MODEL_TABLE);
        assertThat(index.get("is_unique")).isEqualTo(true);
        assertThat(predicate)
            .contains("is_active", "is_searchable", "true");
        assertThat(definition)
            .contains("create unique index", INDEX_NAME, "embedding_models", "((1))", "where");

        String databaseVersion = jdbcTemplate.queryForObject("SELECT version()", String.class);
        System.out.printf(
            "INDEX_CATALOG_RESULT dbVersion=%s index=%s unique=%s predicate=%s definition=%s%n",
            databaseVersion,
            index.get("index_name"),
            index.get("is_unique"),
            index.get("predicate"),
            index.get("index_definition")
        );
    }

    @Test
    @DisplayName("첫 번째 active이면서 searchable인 모델은 Commit된다")
    void firstActiveAndSearchableModel_isCommitted() {
        Long modelId = inNewTransaction(() -> insertModel("first-default-model", true, true));

        assertThat(modelId).isNotNull();
        assertThat(countActiveAndSearchable(MODEL_TABLE)).isEqualTo(1);
    }

    @Test
    @DisplayName("두 번째 active이면서 searchable인 모델은 SQLSTATE 23505로 차단된다")
    void secondActiveAndSearchableModel_isRejectedByIndex() {
        Long firstId = inNewTransaction(() -> insertModel("first-default-model", true, true));

        Throwable thrown = catchThrowable(() ->
            inNewTransaction(() -> insertModel("second-default-model", true, true))
        );

        ViolationDetails violation = assertUniqueViolation(thrown);
        assertThat(violation.constraintName()).isEqualTo(INDEX_NAME);
        assertThat(countActiveAndSearchable(MODEL_TABLE)).isEqualTo(1);
        assertThat(findModelId("first-default-model")).isEqualTo(firstId);

        System.out.printf(
            "UNIQUE_VIOLATION_RESULT sqlState=%s index=%s finalDefaultCount=%d%n",
            violation.sqlState(),
            violation.constraintName(),
            countActiveAndSearchable(MODEL_TABLE)
        );
    }

    @Test
    @DisplayName("기본 모델이 아닌 Boolean 조합은 여러 개 저장할 수 있다")
    void nonDefaultBooleanCombinations_allowMultipleRows() {
        inNewTransaction(() -> {
            insertModel("inactive-a", false, false);
            insertModel("inactive-b", false, false);
            insertModel("experiment-a", true, false);
            insertModel("experiment-b", true, false);
            insertModel("legacy-search-a", false, true);
            insertModel("legacy-search-b", false, true);
            insertModel("default-a", true, true);
            return null;
        });

        assertThat(countByState(false, false)).isEqualTo(2);
        assertThat(countByState(true, false)).isEqualTo(2);
        assertThat(countByState(false, true)).isEqualTo(2);
        assertThat(countByState(true, true)).isEqualTo(1);

        Throwable thrown = catchThrowable(() ->
            inNewTransaction(() -> insertModel("default-b", true, true))
        );
        assertUniqueViolation(thrown);

        assertThat(countAllModels()).isEqualTo(7);
        assertThat(countByState(false, false)).isEqualTo(2);
        assertThat(countByState(true, false)).isEqualTo(2);
        assertThat(countByState(false, true)).isEqualTo(2);
        assertThat(countByState(true, true)).isEqualTo(1);

        System.out.printf(
            "BOOLEAN_COMBINATION_RESULT falseFalse=%d trueFalse=%d falseTrue=%d trueTrue=%d total=%d%n",
            countByState(false, false),
            countByState(true, false),
            countByState(false, true),
            countByState(true, true),
            countAllModels()
        );
    }

    @Test
    @DisplayName("애플리케이션 사전 조회만으로는 동시 중복 저장을 막을 수 없다")
    void applicationPreCheckAlone_hasRaceCondition() throws Exception {
        createRaceComparisonTable();
        traceSequence.set(0);
        trace("T0", "메인 Thread가 동시성 테스트를 시작한다.");

        CyclicBarrier barrier = new CyclicBarrier(
            2,
            () -> trace("T7", "두 트랜잭션이 모두 Barrier에 도착했다. Barrier를 해제한다.")
        );

        trace("T1", "Task A와 Task B를 ExecutorService에 제출한다.");
        List<AttemptResult> results = executeTracedRaceAttempts(barrier);

        assertThat(results).allSatisfy(result -> {
            assertThat(result.preCheckCount()).isZero();
            assertThat(result.success()).isTrue();
        });
        int finalCount = countActiveAndSearchable(RACE_TABLE);
        trace("T11", "최종 TRUE + TRUE 모델 개수 = " + finalCount);
        assertThat(finalCount).isEqualTo(2);

        System.out.printf(
            "PRECHECK_RACE_RESULT attempts=2 success=%d failure=%d finalDefaultCount=%d medianAttemptMs=%.6f%n",
            countSuccessful(results),
            countFailed(results),
            finalCount,
            medianAttemptMillis(results)
        );
    }

    @Test
    @DisplayName("독립 트랜잭션이 동시에 기본 모델을 저장하면 매회 한 건만 성공한다")
    void partialUniqueIndex_protectsAllConcurrentAttempts() throws Exception {
        int totalSuccess = 0;
        int totalUniqueViolation = 0;
        int invariantViolation = 0;
        List<AttemptResult> allResults = new ArrayList<>();

        for (int repetition = 1; repetition <= CONCURRENCY_REPETITIONS; repetition++) {
            jdbcTemplate.update("DELETE FROM " + MODEL_TABLE);
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<AttemptResult> results = executeConcurrentAttempts(
                MODEL_TABLE,
                barrier,
                "concurrency-model-a-" + repetition,
                "concurrency-model-b-" + repetition
            );

            int success = countSuccessful(results);
            int uniqueViolation = (int) results.stream()
                .filter(result -> UNIQUE_VIOLATION_SQL_STATE.equals(result.sqlState()))
                .count();
            int finalCount = countActiveAndSearchable(MODEL_TABLE);

            totalSuccess += success;
            totalUniqueViolation += uniqueViolation;
            allResults.addAll(results);
            if (success != 1 || uniqueViolation != 1 || finalCount != 1) {
                invariantViolation++;
            }

            assertThat(results).extracting(AttemptResult::preCheckCount).containsOnly(0);
            assertThat(success).isEqualTo(1);
            assertThat(uniqueViolation).isEqualTo(1);
            assertThat(results)
                .filteredOn(result -> !result.success())
                .extracting(AttemptResult::constraintName)
                .containsExactly(INDEX_NAME);
            assertThat(finalCount).isEqualTo(1);
        }

        assertThat(totalSuccess).isEqualTo(CONCURRENCY_REPETITIONS);
        assertThat(totalUniqueViolation).isEqualTo(CONCURRENCY_REPETITIONS);
        assertThat(invariantViolation).isZero();

        System.out.printf(
            "CONCURRENCY_RESULT repetitions=%d attempts=%d success=%d uniqueViolation=%d unexpectedFailure=0 invariantViolation=%d medianAttemptMs=%.6f%n",
            CONCURRENCY_REPETITIONS,
            CONCURRENCY_REPETITIONS * 2,
            totalSuccess,
            totalUniqueViolation,
            invariantViolation,
            medianAttemptMillis(allResults)
        );
    }

    @Test
    @DisplayName("기존 모델을 먼저 해제하면 새 기본 모델로 교체할 수 있다")
    void switchModel_succeedsWhenOldModelIsReleasedFirst() {
        insertSwitchModels();

        inNewTransaction(() -> {
            updateModelState("old-model", false, true);
            updateModelState("new-model", true, true);
            return null;
        });

        assertThat(findModelState("old-model")).isEqualTo(new ModelState(false, true));
        assertThat(findModelState("new-model")).isEqualTo(new ModelState(true, true));
        assertThat(countActiveAndSearchable(MODEL_TABLE)).isEqualTo(1);

        System.out.printf(
            "SWITCH_SUCCESS_RESULT old=%s new=%s finalDefaultCount=%d%n",
            findModelState("old-model"),
            findModelState("new-model"),
            countActiveAndSearchable(MODEL_TABLE)
        );
    }

    @Test
    @DisplayName("신규 모델을 먼저 활성화하면 Unique Index가 교체를 차단한다")
    void switchModel_failsWhenNewModelIsActivatedFirst() {
        insertSwitchModels();

        Throwable thrown = catchThrowable(() -> inNewTransaction(() -> {
            updateModelState("new-model", true, true);
            updateModelState("old-model", false, true);
            return null;
        }));

        ViolationDetails violation = assertUniqueViolation(thrown);
        assertThat(findModelState("old-model")).isEqualTo(new ModelState(true, true));
        assertThat(findModelState("new-model")).isEqualTo(new ModelState(false, false));
        assertThat(countActiveAndSearchable(MODEL_TABLE)).isEqualTo(1);

        System.out.printf(
            "SWITCH_WRONG_ORDER_RESULT sqlState=%s index=%s old=%s new=%s finalDefaultCount=%d%n",
            violation.sqlState(),
            violation.constraintName(),
            findModelState("old-model"),
            findModelState("new-model"),
            countActiveAndSearchable(MODEL_TABLE)
        );
    }

    @Test
    @DisplayName("기존 모델 해제 후 오류가 발생하면 전체 변경이 Rollback된다")
    void switchModel_rollsBackWhenErrorOccurs() {
        insertSwitchModels();

        Throwable thrown = catchThrowable(() -> inNewTransaction(() -> {
            updateModelState("old-model", false, true);
            throw new IntentionalTestException();
        }));

        assertThat(thrown).isInstanceOf(IntentionalTestException.class);
        assertThat(findModelState("old-model")).isEqualTo(new ModelState(true, true));
        assertThat(findModelState("new-model")).isEqualTo(new ModelState(false, false));
        assertThat(countActiveAndSearchable(MODEL_TABLE)).isEqualTo(1);

        System.out.printf(
            "SWITCH_ROLLBACK_RESULT exception=%s old=%s new=%s finalDefaultCount=%d%n",
            thrown.getClass().getSimpleName(),
            findModelState("old-model"),
            findModelState("new-model"),
            countActiveAndSearchable(MODEL_TABLE)
        );
    }

    private List<AttemptResult> executeConcurrentAttempts(
        String tableName,
        CyclicBarrier barrier,
        String firstModelName,
        String secondModelName
    ) throws InterruptedException, ExecutionException, TimeoutException {
        List<Future<AttemptResult>> futures = List.of(
            executorService.submit(() -> attemptConcurrentInsert(tableName, firstModelName, barrier)),
            executorService.submit(() -> attemptConcurrentInsert(tableName, secondModelName, barrier))
        );

        List<AttemptResult> results = new ArrayList<>();
        for (Future<AttemptResult> future : futures) {
            results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        return results;
    }

    private List<AttemptResult> executeTracedRaceAttempts(
        CyclicBarrier barrier
    ) throws InterruptedException, ExecutionException, TimeoutException {
        List<Future<AttemptResult>> futures = List.of(
            executorService.submit(() -> {
                trace("T2-A", "Worker Thread가 Task A를 가져갔다.");
                return attemptTracedRaceInsert("A", "race-a", barrier);
            }),
            executorService.submit(() -> {
                trace("T2-B", "Worker Thread가 Task B를 가져갔다.");
                return attemptTracedRaceInsert("B", "race-b", barrier);
            })
        );

        List<AttemptResult> results = new ArrayList<>();
        for (Future<AttemptResult> future : futures) {
            results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        return results;
    }

    private AttemptResult attemptTracedRaceInsert(
        String transactionLabel,
        String modelName,
        CyclicBarrier barrier
    ) {
        int[] preCheckCount = {-1};
        long startedAt = System.nanoTime();

        inNewTransaction(() -> {
            trace("T3-" + transactionLabel, "Transaction " + transactionLabel + "가 시작됐다.");
            registerTransactionTrace(transactionLabel);

            trace("T4-" + transactionLabel, "countDefaultModels() 실행을 시작한다.");
            preCheckCount[0] = countActiveAndSearchable(RACE_TABLE);
            trace(
                "T4-" + transactionLabel,
                "countDefaultModels() 결과 = " + preCheckCount[0]
            );

            trace("T5-" + transactionLabel, "Barrier에 도착했다. 다른 트랜잭션을 기다린다.");
            awaitTracedBarrier(barrier, transactionLabel);

            trace("T8-" + transactionLabel, modelName + " INSERT를 시작한다.");
            jdbcTemplate.update(
                "INSERT INTO " + RACE_TABLE + " (model_name, is_active, is_searchable) VALUES (?, TRUE, TRUE)",
                modelName
            );
            trace(
                "T9-" + transactionLabel,
                modelName + " INSERT SQL 실행이 성공했다. 아직 Commit 완료 상태는 아니다."
            );
            return null;
        });

        return AttemptResult.success(preCheckCount[0], elapsedMillis(startedAt));
    }

    private void registerTransactionTrace(String transactionLabel) {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                trace(
                    "T10-BEFORE-" + transactionLabel,
                    "Transaction " + transactionLabel + " Commit 직전이다."
                );
            }

            @Override
            public void afterCommit() {
                trace(
                    "T10-COMMIT-" + transactionLabel,
                    "Transaction " + transactionLabel + " Commit이 완료됐다."
                );
            }
        });
    }

    private void awaitTracedBarrier(CyclicBarrier barrier, String transactionLabel) {
        awaitBarrier(barrier);
        trace(
            "T7-" + transactionLabel,
            "Barrier가 해제되어 INSERT 단계로 진행한다."
        );
    }

    private AttemptResult attemptConcurrentInsert(String tableName, String modelName, CyclicBarrier barrier) {
        int[] preCheckCount = {-1};
        long startedAt = System.nanoTime();

        try {
            inNewTransaction(() -> {
                preCheckCount[0] = countActiveAndSearchable(tableName);
                awaitBarrier(barrier);
                if (MODEL_TABLE.equals(tableName)) {
                    insertModel(modelName, true, true);
                } else {
                    jdbcTemplate.update(
                        "INSERT INTO " + RACE_TABLE + " (model_name, is_active, is_searchable) VALUES (?, TRUE, TRUE)",
                        modelName
                    );
                }
                return null;
            });
            return AttemptResult.success(preCheckCount[0], elapsedMillis(startedAt));
        } catch (DataAccessException exception) {
            PSQLException postgresException = findPostgresException(exception);
            if (postgresException == null || !UNIQUE_VIOLATION_SQL_STATE.equals(postgresException.getSQLState())) {
                throw exception;
            }
            String constraintName = postgresException.getServerErrorMessage() == null
                ? null
                : postgresException.getServerErrorMessage().getConstraint();
            return AttemptResult.uniqueViolation(
                preCheckCount[0],
                postgresException.getSQLState(),
                constraintName,
                elapsedMillis(startedAt)
            );
        }
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 Barrier 대기 중 Thread가 중단되었습니다.", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException("동시성 테스트 Barrier가 제한 시간 안에 완료되지 않았습니다.", exception);
        }
    }

    private ViolationDetails assertUniqueViolation(Throwable throwable) {
        assertThat(throwable).isInstanceOf(DataIntegrityViolationException.class);
        PSQLException postgresException = findPostgresException(throwable);
        assertThat((Object) postgresException).isNotNull();
        assertThat(postgresException.getSQLState()).isEqualTo(UNIQUE_VIOLATION_SQL_STATE);
        assertThat(postgresException.getServerErrorMessage()).isNotNull();

        String constraintName = postgresException.getServerErrorMessage().getConstraint();
        assertThat(constraintName).isEqualTo(INDEX_NAME);
        return new ViolationDetails(postgresException.getSQLState(), constraintName);
    }

    private PSQLException findPostgresException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PSQLException postgresException) {
                return postgresException;
            }
            current = current.getCause();
        }
        return null;
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate.execute(status -> work.get());
    }

    private Long insertModel(String modelName, boolean isActive, boolean isSearchable) {
        return jdbcTemplate.queryForObject(INSERT_MODEL_SQL, Long.class, modelName, isActive, isSearchable);
    }

    private void insertSwitchModels() {
        inNewTransaction(() -> {
            insertModel("old-model", true, true);
            insertModel("new-model", false, false);
            return null;
        });
    }

    private void updateModelState(String modelName, boolean isActive, boolean isSearchable) {
        int updated = jdbcTemplate.update("""
            UPDATE embedding_models
            SET is_active = ?,
                is_searchable = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE model_name = ?
            """, isActive, isSearchable, modelName);
        assertThat(updated).isEqualTo(1);
    }

    private ModelState findModelState(String modelName) {
        return jdbcTemplate.queryForObject("""
            SELECT is_active, is_searchable
            FROM embedding_models
            WHERE model_name = ?
            """, (resultSet, rowNumber) -> new ModelState(
                resultSet.getBoolean("is_active"),
                resultSet.getBoolean("is_searchable")
            ), modelName);
    }

    private Long findModelId(String modelName) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM embedding_models WHERE model_name = ?",
            Long.class,
            modelName
        );
    }

    private int countAllModels() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM embedding_models", Integer.class);
    }

    private int countByState(boolean isActive, boolean isSearchable) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM embedding_models
            WHERE is_active = ?
              AND is_searchable = ?
            """, Integer.class, isActive, isSearchable);
    }

    private int countActiveAndSearchable(String tableName) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + tableName + " WHERE is_active = TRUE AND is_searchable = TRUE",
            Integer.class
        );
    }

    private void createRaceComparisonTable() {
        jdbcTemplate.execute("""
            CREATE TABLE embedding_models_race_without_constraint (
                id BIGSERIAL PRIMARY KEY,
                model_name VARCHAR(200) NOT NULL,
                is_active BOOLEAN NOT NULL,
                is_searchable BOOLEAN NOT NULL
            )
            """);
    }

    private int countSuccessful(List<AttemptResult> results) {
        return (int) results.stream().filter(AttemptResult::success).count();
    }

    private int countFailed(List<AttemptResult> results) {
        return results.size() - countSuccessful(results);
    }

    private double medianAttemptMillis(List<AttemptResult> results) {
        List<Double> durations = results.stream()
            .map(AttemptResult::elapsedMillis)
            .sorted()
            .toList();
        int middle = durations.size() / 2;
        if (durations.size() % 2 == 0) {
            return (durations.get(middle - 1) + durations.get(middle)) / 2.0;
        }
        return durations.get(middle);
    }

    private double elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000.0;
    }

    private void trace(String step, String message) {
        synchronized (traceMonitor) {
            int sequence = traceSequence.incrementAndGet();
            String now = LocalTime.now().format(TRACE_TIME_FORMATTER);
            String threadName = Thread.currentThread().getName();
            boolean transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            String transactionName = TransactionSynchronizationManager.getCurrentTransactionName();

            System.out.printf(
                "[%02d] [%s] [%-20s] [%-12s] txActive=%-5s txName=%s | %s%n",
                sequence,
                now,
                threadName,
                step,
                transactionActive,
                transactionName,
                message
            );
        }
    }

    private record AttemptResult(
        boolean success,
        int preCheckCount,
        String sqlState,
        String constraintName,
        double elapsedMillis
    ) {
        private static AttemptResult success(int preCheckCount, double elapsedMillis) {
            return new AttemptResult(true, preCheckCount, null, null, elapsedMillis);
        }

        private static AttemptResult uniqueViolation(
            int preCheckCount,
            String sqlState,
            String constraintName,
            double elapsedMillis
        ) {
            return new AttemptResult(false, preCheckCount, sqlState, constraintName, elapsedMillis);
        }
    }

    private record ViolationDetails(String sqlState, String constraintName) {
    }

    private record ModelState(boolean isActive, boolean isSearchable) {
    }

    private static class IntentionalTestException extends RuntimeException {
    }
}
