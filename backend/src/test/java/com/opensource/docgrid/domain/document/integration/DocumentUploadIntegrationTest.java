package com.opensource.docgrid.domain.document.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.service.FileHashService;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 문서 업로드가 파일·문서·버전·Embedding Job을 하나의 흐름으로 생성하는지 검증하는 통합 테스트.
 *
 * <p>실제 기본 모델 Seed인 {@code BAAI/bge-m3}를 기준으로 정상 생성, 파일 중복 제거, 동시 업로드,
 * 기본 모델 부재 시 Rollback과 저장소 보상 삭제를 확인한다.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("문서 업로드 통합 테스트")
class DocumentUploadIntegrationTest {

    @Autowired private DocumentUploadFacade documentUploadFacade;
    @Autowired private FileHashService fileHashService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private FileStorageService fileStorageService;

    private final List<DocumentUploadResponse> createdResponses = new ArrayList<>();
    private Long userId;
    private String testSuffix;

    @BeforeEach
    void setUp() {
        reset(fileStorageService);
        testSuffix = UUID.randomUUID().toString();
        userId = userRepository.findByEmail("kcw130502@gmail.com").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        for (DocumentUploadResponse response : createdResponses) {
            jdbcTemplate.update("DELETE FROM embedding_jobs WHERE id = ?", response.embeddingJobId());
            jdbcTemplate.update(
                "DELETE FROM sync_outbox_events WHERE aggregate_type = 'DOCUMENT_VERSION' AND aggregate_id = ?",
                response.documentVersionId()
            );
            jdbcTemplate.update("UPDATE documents SET current_version_id = NULL WHERE id = ?", response.documentId());
            jdbcTemplate.update("DELETE FROM document_versions WHERE id = ?", response.documentVersionId());
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", response.documentId());
        }
        createdResponses.stream()
            .map(DocumentUploadResponse::fileObjectId)
            .distinct()
            .forEach(id -> jdbcTemplate.update("DELETE FROM file_objects WHERE id = ?", id));
    }

    @Test
    @DisplayName("정상 TXT 업로드 시 네 테이블과 연관관계를 생성한다")
    void upload_createsAllReceptionData() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willReturn(new StoredFile("test-bucket", "documents/test/file.txt"));
        DocumentUploadRequest request = request("normal-" + testSuffix, "정상 업로드 " + testSuffix);

        DocumentUploadResponse response = documentUploadFacade.upload(userId, request);
        createdResponses.add(response);

        assertThat(countById("file_objects", response.fileObjectId())).isOne();
        assertThat(countById("documents", response.documentId())).isOne();
        assertThat(countById("document_versions", response.documentVersionId())).isOne();
        assertThat(countById("embedding_jobs", response.embeddingJobId())).isOne();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT current_version_id FROM documents WHERE id = ?", Long.class, response.documentId()
        )).isEqualTo(response.documentVersionId());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT file_object_id FROM document_versions WHERE id = ?", Long.class, response.documentVersionId()
        )).isEqualTo(response.fileObjectId());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM embedding_jobs WHERE id = ?", String.class, response.embeddingJobId()
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT retry_count FROM embedding_jobs WHERE id = ?", Integer.class, response.embeddingJobId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT max_retry_count FROM embedding_jobs WHERE id = ?", Integer.class, response.embeddingJobId()
        )).isEqualTo(3);
        UUID sourceEventId = jdbcTemplate.queryForObject(
            "SELECT source_event_id FROM embedding_jobs WHERE id = ?",
            UUID.class,
            response.embeddingJobId()
        );
        assertThat(sourceEventId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_outbox_events "
                + "WHERE event_id = ? AND aggregate_type = 'DOCUMENT_VERSION' AND aggregate_id = ? "
                + "AND event_type = 'DOCUMENT_VERSION_CREATED' AND status = 'PENDING'",
            Integer.class,
            sourceEventId,
            response.documentVersionId()
        )).isOne();
    }

    @Test
    @DisplayName("같은 파일을 순차 업로드하면 기존 FileObject를 재사용한다")
    void upload_reusesFileObject_when_sameFileIsUploadedSequentially() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willReturn(new StoredFile("test-bucket", "documents/test/sequential.txt"));
        String content = "sequential-" + testSuffix;

        DocumentUploadResponse first = documentUploadFacade.upload(
            userId, request(content, "순차 업로드 1 " + testSuffix)
        );
        DocumentUploadResponse second = documentUploadFacade.upload(
            userId, request(content, "순차 업로드 2 " + testSuffix)
        );
        createdResponses.add(first);
        createdResponses.add(second);

        assertThat(first.fileObjectId()).isEqualTo(second.fileObjectId());
        assertThat(countById("file_objects", first.fileObjectId())).isOne();
        assertThat(countById("documents", first.documentId())).isOne();
        assertThat(countById("documents", second.documentId())).isOne();
        verify(fileStorageService, times(1))
            .store(any(InputStream.class), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("같은 파일을 동시에 업로드해도 FileObject는 하나만 생성한다")
    void upload_createsSingleFileObject_when_sameFileIsUploadedConcurrently() throws Exception {
        CyclicBarrier storageBarrier = new CyclicBarrier(2);
        AtomicInteger objectSequence = new AtomicInteger();
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> {
                storageBarrier.await(10, TimeUnit.SECONDS);
                return new StoredFile("test-bucket", "documents/test/candidate-" + objectSequence.incrementAndGet());
            });
        String content = "concurrent-" + testSuffix;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DocumentUploadResponse> first = executor.submit(
                () -> documentUploadFacade.upload(userId, request(content, "동시 업로드 1 " + testSuffix))
            );
            Future<DocumentUploadResponse> second = executor.submit(
                () -> documentUploadFacade.upload(userId, request(content, "동시 업로드 2 " + testSuffix))
            );

            DocumentUploadResponse firstResponse = first.get(15, TimeUnit.SECONDS);
            DocumentUploadResponse secondResponse = second.get(15, TimeUnit.SECONDS);
            createdResponses.add(firstResponse);
            createdResponses.add(secondResponse);

            assertThat(firstResponse.fileObjectId()).isEqualTo(secondResponse.fileObjectId());
            assertThat(countById("file_objects", firstResponse.fileObjectId())).isOne();
            assertThat(countById("documents", firstResponse.documentId())).isOne();
            assertThat(countById("documents", secondResponse.documentId())).isOne();
            verify(fileStorageService, times(1)).delete(any(StoredFile.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("active EmbeddingModel이 없으면 DB를 롤백하고 후보 Object를 삭제한다")
    void upload_rollsBackAndDeletesCandidate_when_activeModelDoesNotExist() {
        StoredFile candidate = new StoredFile("test-bucket", "documents/test/rollback-candidate");
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willReturn(candidate);
        DocumentUploadRequest request = request("rollback-" + testSuffix, "롤백 검증 " + testSuffix);
        String fileHash = fileHashService.calculateSha256(request.file());
        jdbcTemplate.update("UPDATE embedding_models SET is_active = FALSE WHERE is_active = TRUE AND is_searchable = TRUE");

        try {
            assertThatThrownBy(() -> documentUploadFacade.upload(userId, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_MODEL_NOT_CONFIGURED);
        } finally {
            // 후속 테스트가 실제 운영 Seed를 다시 기본 모델로 조회할 수 있도록 비활성화 변경을 복구한다.
            jdbcTemplate.update("""
                UPDATE embedding_models
                   SET is_active = TRUE
                 WHERE model_name = 'BAAI/bge-m3'
                   AND model_version = '1.0'
                """);
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM file_objects WHERE file_hash = ?", Integer.class, fileHash
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM documents WHERE title = ?", Integer.class, request.title()
        )).isZero();
        verify(fileStorageService).delete(candidate);
    }

    private DocumentUploadRequest request(String content, String title) {
        MockMultipartFile file = new MockMultipartFile(
            "file", "sample.txt", "text/plain", content.getBytes()
        );
        return new DocumentUploadRequest(file, title, "통합 테스트", VisibilityType.PRIVATE);
    }

    private int countById(String table, Long id) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id
        );
    }
}
