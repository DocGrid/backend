package com.opensource.docgrid.domain.embedding.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

/**
 * 대시보드 집계에 쓰이는 평균 처리 시간 Native Query를 실제 OpenSQL에서 검증한다.
 *
 * <p>Queue 대기 시간({@code created_at})이 아니라 Worker 처리 구간({@code started_at}~{@code completed_at})만
 * 반영하는지, 완료된 Job이 없을 때 {@code null}을 반환하는지를 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("EmbeddingJob 대시보드 집계 Repository 테스트")
class EmbeddingJobDashboardRepositoryTest {

    private static final String TEST_SCHEMA = "docgrid_embedding_job_dashboard_repository_test";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EmbeddingJobRepository embeddingJobRepository;

    private Long documentId;
    private Long versionId;
    private Long embeddingModelId;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-embedding-job-dashboard-repository-test-secret-key-2026");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                embedding_jobs,
                document_versions,
                documents,
                users
            RESTART IDENTITY CASCADE
            """);

        String suffix = UUID.randomUUID().toString();
        Long userId = insertUser(suffix);
        documentId = insertDocument(userId);
        versionId = insertVersion(documentId, userId);
        embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM embedding_models
            WHERE is_active = TRUE AND is_searchable = TRUE
            """, Long.class);
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("Queue 대기 시간은 제외하고 started_at~completed_at 구간만 평균에 반영한다")
    void findAverageProcessingMillis_excludesQueueWaitTime() {
        // created_at을 started_at보다 훨씬 이전으로 둬서, 평균 계산이 대기 시간을 섞지 않는지 확인한다.
        insertIndexedJob("2026-08-01 00:00:00", "2026-08-10 00:00:00", "2026-08-10 00:00:02");

        Double averageMillis = embeddingJobRepository.findAverageProcessingMillis();

        assertThat(averageMillis).isCloseTo(2000.0, within(1.0));
    }

    @Test
    @DisplayName("완료된 Job이 없으면 null을 반환한다")
    void findAverageProcessingMillis_returnsNull_whenNoCompletedJobs() {
        insertPendingJob();

        Double averageMillis = embeddingJobRepository.findAverageProcessingMillis();

        assertThat(averageMillis).isNull();
    }

    @Test
    @DisplayName("여러 완료 Job의 처리 시간을 평균낸다")
    void findAverageProcessingMillis_averagesMultipleJobs() {
        insertIndexedJob("2026-08-10 00:00:00", "2026-08-10 00:00:00", "2026-08-10 00:00:01");
        insertIndexedJob("2026-08-10 00:00:00", "2026-08-10 00:00:00", "2026-08-10 00:00:03");

        Double averageMillis = embeddingJobRepository.findAverageProcessingMillis();

        assertThat(averageMillis).isCloseTo(2000.0, within(1.0));
    }

    private Long insertUser(String suffix) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Dashboard Repository Test User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "embedding-job-dashboard-repository-" + suffix + "@example.com");
    }

    private Long insertDocument(Long userId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility,
                created_at, updated_at
            )
            VALUES (?, 'Dashboard Repository Test Document', 'TXT', 'UPLOAD', 'INDEXING', 'PRIVATE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId);
    }

    private Long insertVersion(Long targetDocumentId, Long userId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status,
                created_by, created_at, updated_at
            )
            VALUES (?, 1, 'Dashboard Repository Test Version', 'text/plain', 'EMBEDDING', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, targetDocumentId, userId);
    }

    private void insertIndexedJob(String createdAt, String startedAt, String completedAt) {
        jdbcTemplate.update("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, started_at, completed_at, created_at, updated_at
            )
            VALUES (?, ?, 'INDEXED', 0, 0, 3, ?::timestamp, ?::timestamp, ?::timestamp, ?::timestamp)
            """, versionId, embeddingModelId, startedAt, completedAt, createdAt, createdAt);
    }

    private void insertPendingJob() {
        jdbcTemplate.update("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, created_at, updated_at
            )
            VALUES (?, ?, 'PENDING', 0, 0, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, versionId, embeddingModelId);
    }
}
