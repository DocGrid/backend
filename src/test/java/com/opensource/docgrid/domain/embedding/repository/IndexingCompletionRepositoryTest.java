package com.opensource.docgrid.domain.embedding.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;

/**
 * 인덱싱 완료용 Repository 집계와 Embedding 일괄 상태 전환을 실제 OpenSQL에서 검증한다.
 *
 * <p>Vector 본문을 Entity로 읽지 않고도 Version·Model·상태 개수와 관계·차원·Hash 불변식을 판별하고,
 * 이전 ACTIVE Set을 STALE로 전환하는 계약을 격리 Schema에서 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("인덱싱 완료 Repository 테스트")
class IndexingCompletionRepositoryTest {

    private static final String TEST_SCHEMA = "docgrid_index_completion_repository_test";
    private static final int VECTOR_DIMENSION = 1024;
    private static final String VECTOR_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EmbeddingRepository embeddingRepository;
    @Autowired private EmbeddingJobRepository embeddingJobRepository;
    @Autowired private IndexingEventRepository indexingEventRepository;

    private Long documentId;
    private Long versionId;
    private Long embeddingModelId;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-index-completion-repository-test-secret-key-2026");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                embeddings,
                indexing_events,
                document_chunks,
                embedding_job_attempts,
                embedding_jobs,
                document_versions,
                documents,
                users
            RESTART IDENTITY CASCADE
            """);

        String suffix = UUID.randomUUID().toString();
        Long userId = insertUser(suffix);
        documentId = insertDocument(userId, "Repository Contract Document");
        versionId = insertVersion(documentId, userId, 1);
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
    @DisplayName("Version의 전체·Model·ACTIVE 개수를 집계하고 ACTIVE Set을 STALE로 전환한다")
    void countsAndMarksActiveSetStale() {
        List<Long> chunkIds = insertChunks(versionId, 2);
        insertEmbedding(chunkIds.get(0), documentId, versionId, embeddingModelId, VECTOR_HASH);
        insertEmbedding(chunkIds.get(1), documentId, versionId, embeddingModelId, VECTOR_HASH);

        assertThat(embeddingRepository.countByDocumentVersionId(versionId)).isEqualTo(2);
        assertThat(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(
            versionId,
            embeddingModelId
        )).isEqualTo(2);
        assertThat(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
            versionId,
            embeddingModelId,
            EmbeddingStatus.ACTIVE
        )).isEqualTo(2);
        assertThat(embeddingRepository.countInvalidCompletionRows(
            documentId,
            versionId,
            embeddingModelId,
            VECTOR_DIMENSION
        )).isZero();

        int updatedRows = embeddingRepository.markActiveAsStaleByDocumentVersionId(versionId);

        assertThat(updatedRows).isEqualTo(2);
        assertThat(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
            versionId,
            embeddingModelId,
            EmbeddingStatus.ACTIVE
        )).isZero();
    }

    @Test
    @DisplayName("역정규화 관계나 Vector Hash가 잘못된 행을 완료 불변식 위반으로 집계한다")
    void countInvalidCompletionRows_detectsBrokenInvariant() {
        Long chunkId = insertChunks(versionId, 1).get(0);
        insertEmbedding(chunkId, documentId, versionId, embeddingModelId, "INVALID");

        long invalidRows = embeddingRepository.countInvalidCompletionRows(
            documentId,
            versionId,
            embeddingModelId,
            VECTOR_DIMENSION
        );

        assertThat(invalidRows).isOne();
    }

    @Test
    @DisplayName("Version의 진행 Job과 Job의 INDEXED 이벤트 수를 집계한다")
    void countsActiveJobsAndIndexedEvents() {
        Long processingJobId = insertJob(EmbeddingJobStatus.PROCESSING);
        insertJob(EmbeddingJobStatus.INDEXED);
        jdbcTemplate.update("""
            INSERT INTO indexing_events (
                embedding_job_id, event_type, from_status, to_status, message,
                occurred_at, created_at, updated_at
            )
            VALUES (?, 'INDEXED', 'EMBEDDING', 'INDEXED', '완료',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, processingJobId);

        assertThat(embeddingJobRepository.countByDocumentVersionIdAndStatusIn(
            versionId,
            List.of(EmbeddingJobStatus.PENDING, EmbeddingJobStatus.PROCESSING)
        )).isOne();
        assertThat(indexingEventRepository.countByEmbeddingJobIdAndEventType(
            processingJobId,
            IndexingEventType.INDEXED
        )).isOne();
    }

    private Long insertUser(String suffix) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Repository Test User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "index-completion-repository-" + suffix + "@example.com");
    }

    private Long insertDocument(Long userId, String title) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility,
                created_at, updated_at
            )
            VALUES (?, ?, 'TXT', 'UPLOAD', 'INDEXING', 'PRIVATE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId, title);
    }

    private Long insertVersion(Long targetDocumentId, Long userId, int versionNo) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status,
                created_by, created_at, updated_at
            )
            VALUES (?, ?, 'Repository Test Version', 'text/plain', 'EMBEDDING', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, targetDocumentId, versionNo, userId);
    }

    private List<Long> insertChunks(Long targetVersionId, int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> jdbcTemplate.queryForObject("""
                INSERT INTO document_chunks (
                    document_version_id, chunk_index, chunk_text, token_count,
                    char_start, char_end, content_hash, created_at, updated_at
                )
                VALUES (?, ?, ?, 1, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class,
                targetVersionId,
                index,
                "본문-" + index,
                index,
                index + 1,
                VECTOR_HASH
            ))
            .toList();
    }

    private void insertEmbedding(
        Long chunkId,
        Long targetDocumentId,
        Long targetVersionId,
        Long modelId,
        String vectorHash
    ) {
        jdbcTemplate.update("""
            INSERT INTO embeddings (
                chunk_id, document_id, document_version_id, embedding_model_id,
                vector, dimension, vector_hash, status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            chunkId,
            targetDocumentId,
            targetVersionId,
            modelId,
            zeroVector(),
            VECTOR_DIMENSION,
            vectorHash
        );
    }

    private Long insertJob(EmbeddingJobStatus status) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, created_at, updated_at
            )
            VALUES (?, ?, ?, 0, 0, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, versionId, embeddingModelId, status.name());
    }

    private String zeroVector() {
        return "[" + "0,".repeat(VECTOR_DIMENSION - 1) + "0]";
    }
}
