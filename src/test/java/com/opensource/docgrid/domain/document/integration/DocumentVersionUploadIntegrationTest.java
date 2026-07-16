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
import com.opensource.docgrid.domain.document.dto.request.DocumentVersionUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.service.DocumentVersionUploadFacade;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("문서 새 버전 업로드 통합 테스트")
class DocumentVersionUploadIntegrationTest {

    @Autowired private DocumentUploadFacade documentUploadFacade;
    @Autowired private DocumentVersionUploadFacade documentVersionUploadFacade;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private FileStorageService fileStorageService;

    private final List<Long> documentIds = new ArrayList<>();
    private Long userId;

    @BeforeEach
    void setUp() {
        reset(fileStorageService);
        userId = userRepository.findByEmail("kcw130502@gmail.com").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        for (Long documentId : documentIds) {
            List<Long> fileObjectIds = jdbcTemplate.queryForList(
                "SELECT file_object_id FROM document_versions WHERE document_id = ? AND file_object_id IS NOT NULL",
                Long.class,
                documentId
            );
            jdbcTemplate.update("DELETE FROM embedding_jobs WHERE document_version_id IN "
                + "(SELECT id FROM document_versions WHERE document_id = ?)", documentId);
            jdbcTemplate.update("UPDATE documents SET current_version_id = NULL WHERE id = ?", documentId);
            jdbcTemplate.update("DELETE FROM document_versions WHERE document_id = ?", documentId);
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
            fileObjectIds.stream().distinct().forEach(fileObjectId -> jdbcTemplate.update(
                "DELETE FROM file_objects WHERE id = ? AND NOT EXISTS "
                    + "(SELECT 1 FROM document_versions WHERE file_object_id = ?)",
                fileObjectId,
                fileObjectId
            ));
        }
    }

    @Test
    @DisplayName("길이가 같아도 내용이 다르면 v2를 만들고 current version은 유지한다")
    void upload_createsVersion_when_sameSizeButContentDiffers() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile("test-bucket", "documents/test/" + UUID.randomUUID()));
        DocumentUploadResponse initial = createIndexedDocument("AAAA");

        DocumentVersionUploadResponse response = documentVersionUploadFacade.upload(
            userId,
            initial.documentId(),
            versionRequest("BBBB", "changed.txt")
        );

        assertThat(response.versionNo()).isEqualTo(2);
        assertThat(response.currentVersionId()).isEqualTo(initial.documentVersionId());
        assertThat(response.documentStatus().name()).isEqualTo("INDEXED");
        assertThat(response.versionStatus().name()).isEqualTo("UPLOADED");
        assertThat(response.jobStatus().name()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT original_filename FROM document_versions WHERE id = ?",
            String.class,
            response.documentVersionId()
        )).isEqualTo("changed.txt");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT content_type FROM document_versions WHERE id = ?",
            String.class,
            response.documentVersionId()
        )).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("현재 버전과 같은 파일이면 저장소 업로드 없이 409 예외를 반환한다")
    void upload_rejectsSameCurrentFile_beforeStorage() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile("test-bucket", "documents/test/" + UUID.randomUUID()));
        DocumentUploadResponse initial = createIndexedDocument("same-content");
        reset(fileStorageService);

        assertThatThrownBy(() -> documentVersionUploadFacade.upload(
            userId,
            initial.documentId(),
            versionRequest("same-content", "renamed.txt")
        ))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_VERSION_SAME_CONTENT);

        verify(fileStorageService, times(0))
            .store(any(InputStream.class), anyLong(), anyString(), anyString());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_versions WHERE document_id = ?",
            Integer.class,
            initial.documentId()
        )).isOne();
    }

    @Test
    @DisplayName("과거 버전 파일로 되돌리면 기존 FileObject를 재사용해 새 버전을 만든다")
    void upload_reusesHistoricalFileObject_when_revertingContent() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile("test-bucket", "documents/test/" + UUID.randomUUID()));
        DocumentUploadResponse initial = createIndexedDocument("version-A");
        DocumentVersionUploadResponse second = documentVersionUploadFacade.upload(
            userId, initial.documentId(), versionRequest("version-B", "second.txt")
        );
        jdbcTemplate.update("UPDATE document_versions SET status = 'INDEXED' WHERE id = ?", second.documentVersionId());
        jdbcTemplate.update(
            "UPDATE documents SET current_version_id = ?, status = 'INDEXED' WHERE id = ?",
            second.documentVersionId(),
            initial.documentId()
        );
        reset(fileStorageService);

        DocumentVersionUploadResponse reverted = documentVersionUploadFacade.upload(
            userId, initial.documentId(), versionRequest("version-A", "reverted.txt")
        );

        Long initialFileObjectId = jdbcTemplate.queryForObject(
            "SELECT file_object_id FROM document_versions WHERE id = ?",
            Long.class,
            initial.documentVersionId()
        );
        Long revertedFileObjectId = jdbcTemplate.queryForObject(
            "SELECT file_object_id FROM document_versions WHERE id = ?",
            Long.class,
            reverted.documentVersionId()
        );
        assertThat(reverted.versionNo()).isEqualTo(3);
        assertThat(revertedFileObjectId).isEqualTo(initialFileObjectId);
        verify(fileStorageService, times(0))
            .store(any(InputStream.class), anyLong(), anyString(), anyString());
    }

    private DocumentUploadResponse createIndexedDocument(String content) {
        DocumentUploadRequest request = new DocumentUploadRequest(
            new MockMultipartFile("file", "initial.txt", "text/plain", content.getBytes()),
            "version-test-" + UUID.randomUUID(),
            "통합 테스트",
            VisibilityType.PRIVATE
        );
        DocumentUploadResponse response = documentUploadFacade.upload(userId, request);
        documentIds.add(response.documentId());
        jdbcTemplate.update("UPDATE document_versions SET status = 'INDEXED' WHERE id = ?", response.documentVersionId());
        jdbcTemplate.update("UPDATE documents SET status = 'INDEXED' WHERE id = ?", response.documentId());
        return response;
    }

    private DocumentVersionUploadRequest versionRequest(String content, String filename) {
        return new DocumentVersionUploadRequest(
            new MockMultipartFile("file", filename, "text/plain", content.getBytes())
        );
    }
}
