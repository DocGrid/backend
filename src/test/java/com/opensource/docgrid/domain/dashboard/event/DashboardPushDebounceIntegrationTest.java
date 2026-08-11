package com.opensource.docgrid.domain.dashboard.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.opensource.docgrid.domain.embedding.event.EmbeddingJobStatusChangedEvent;

import jakarta.persistence.EntityManagerFactory;

/**
 * Burst 상황(짧은 시간 내 다건 상태 전이 이벤트)에서 debounce가 실제로 DB 부하를 억제하는지
 * Hibernate {@link Statistics}로 정확한 쿼리 수를 세서 검증한다.
 *
 * <p>이벤트 27개를 각각 {@code REQUIRES_NEW}로 독립 커밋시켜, retry-all이나 장애로 여러 Job이
 * 거의 동시에 상태를 바꾸는 상황을 재현한다. debounce가 없다면 이벤트마다
 * {@code DashboardQueryService.getSummary()}(약 9개 쿼리)가 실행돼 27 × 9 ≈ 243개 쿼리가
 * 나가야 하지만, debounce 적용 후에는 주기당 최대 1회 집계분 수준에서 멈춰야 한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dashboard push debounce burst 통합 테스트")
class DashboardPushDebounceIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_dashboard_push_debounce_test";
    private static final int BURST_EVENT_COUNT = 27;
    // 27개 REQUIRES_NEW 커밋이 한 debounce 주기 안에 다 들어오도록 넉넉히 잡는다.
    private static final String DEBOUNCE_INTERVAL_MS = "500";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Statistics statistics;
    private TransactionTemplate requiresNewTransactionTemplate;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-dashboard-push-debounce-test-secret-key-2026");
        registry.add("dashboard.push.debounce-interval-ms", () -> DEBOUNCE_INTERVAL_MS);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE embedding_jobs, document_versions, documents, users
            RESTART IDENTITY CASCADE
            """);

        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("burst 상황에서도 집계 쿼리는 debounce 주기당 1회 수준으로 억제된다")
    void burstEvents_areCoalescedIntoSingleAggregationQuery() {
        // Given — clear() 이후부터 정확히 세기 시작한다.
        statistics.clear();

        // When — 27개 상태 전이를 각각 독립 트랜잭션으로 커밋시켜 burst를 재현한다.
        // 바깥 트랜잭션에 그냥 참여시키면 커밋이 하나로 묶여 burst 자체가 재현되지 않는다.
        for (long jobId = 1; jobId <= BURST_EVENT_COUNT; jobId++) {
            long publishedJobId = jobId;
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                applicationEventPublisher.publishEvent(new EmbeddingJobStatusChangedEvent(publishedJobId))
            );
        }

        // Then — debounce 스케줄러가 주기당 최대 1회만 집계를 계산했는지 확인한다.
        // (27 × 9 ≈ 243개가 아니라 1회 집계분인 9개 안팎에서 멈춰야 한다)
        await().atMost(Duration.ofSeconds(3))
            .untilAsserted(() -> {
                long queryCount = statistics.getPrepareStatementCount();
                assertThat(queryCount).isPositive();
                assertThat(queryCount).isLessThan(20);
            });
    }
}
