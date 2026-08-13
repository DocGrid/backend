package com.opensource.docgrid.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("문서 상태 Projection Repository 테스트")
class DocumentStatusRepositoryTest {

    private static final EnumSet<DocumentVersionStatus> PROCESSING_VERSION_STATUSES = EnumSet.of(
        DocumentVersionStatus.UPLOADED,
        DocumentVersionStatus.PARSING,
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING
    );
    private static final EnumSet<EmbeddingJobStatus> ACTIVE_JOB_STATUSES = EnumSet.of(
        EmbeddingJobStatus.PENDING,
        EmbeddingJobStatus.PROCESSING
    );

    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private EmbeddingJobRepository embeddingJobRepository;
    @Autowired private EmbeddingModelRepository embeddingModelRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("최초 버전 처리 중에는 같은 버전을 current와 processing 상태로 조회한다")
    void findDocumentStatus_returnsInitialProcessingVersion() {
        User owner = saveOwner();
        Document document = saveDocument(owner, DocumentStatus.UPLOADED);
        DocumentVersion version = saveVersion(document, owner, 1, DocumentVersionStatus.UPLOADED);
        document.updateCurrentVersion(version);
        saveJob(version, EmbeddingJobStatus.PENDING);
        flushAndClear();

        DocumentStatusProjection result = findSingleStatus(document.getId());

        assertThat(result.getDocumentStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(result.getCurrentVersionNo()).isEqualTo(1);
        assertThat(result.getCurrentVersionStatus()).isEqualTo(DocumentVersionStatus.UPLOADED);
        assertThat(result.getProcessingVersionNo()).isEqualTo(1);
        assertThat(result.getProcessingVersionStatus()).isEqualTo(DocumentVersionStatus.UPLOADED);
        assertThat(result.getProcessingJobStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
    }

    @Test
    @DisplayName("새 버전 처리 중에는 기존 INDEXED 버전과 처리 중 버전을 함께 조회한다")
    void findDocumentStatus_returnsCurrentAndProcessingVersions() {
        User owner = saveOwner();
        Document document = saveDocument(owner, DocumentStatus.INDEXED);
        DocumentVersion currentVersion = saveVersion(document, owner, 1, DocumentVersionStatus.INDEXED);
        document.updateCurrentVersion(currentVersion);
        DocumentVersion processingVersion = saveVersion(document, owner, 2, DocumentVersionStatus.PARSING);
        saveJob(processingVersion, EmbeddingJobStatus.PROCESSING);
        flushAndClear();

        DocumentStatusProjection result = findSingleStatus(document.getId());

        assertThat(result.getCurrentVersionNo()).isEqualTo(1);
        assertThat(result.getCurrentVersionStatus()).isEqualTo(DocumentVersionStatus.INDEXED);
        assertThat(result.getProcessingVersionNo()).isEqualTo(2);
        assertThat(result.getProcessingVersionStatus()).isEqualTo(DocumentVersionStatus.PARSING);
        assertThat(result.getProcessingJobStatus()).isEqualTo(EmbeddingJobStatus.PROCESSING);
    }

    @Test
    @DisplayName("처리 중 버전이 없으면 processing 상태를 null로 조회한다")
    void findDocumentStatus_returnsNullProcessingStatus_when_indexingIsComplete() {
        User owner = saveOwner();
        Document document = saveDocument(owner, DocumentStatus.INDEXED);
        DocumentVersion currentVersion = saveVersion(document, owner, 1, DocumentVersionStatus.INDEXED);
        document.updateCurrentVersion(currentVersion);
        flushAndClear();

        DocumentStatusProjection result = findSingleStatus(document.getId());

        assertThat(result.getCurrentVersionNo()).isEqualTo(1);
        assertThat(result.getCurrentVersionStatus()).isEqualTo(DocumentVersionStatus.INDEXED);
        assertThat(result.getProcessingVersionNo()).isNull();
        assertThat(result.getProcessingVersionStatus()).isNull();
        assertThat(result.getProcessingJobStatus()).isNull();
    }

    private User saveOwner() {
        return userRepository.save(
            User.builder()
                .email("document-status-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("문서 상태 테스트 사용자")
                .status(UserStatus.ACTIVE)
                .build()
        );
    }

    private Document saveDocument(User owner, DocumentStatus status) {
        return documentRepository.save(
            Document.builder()
                .owner(owner)
                .title("문서 상태 테스트")
                .documentType(DocumentType.TXT)
                .sourceType(DocumentSourceType.UPLOAD)
                .status(status)
                .visibility(VisibilityType.PRIVATE)
                .build()
        );
    }

    private DocumentVersion saveVersion(
        Document document,
        User owner,
        int versionNo,
        DocumentVersionStatus status
    ) {
        return documentVersionRepository.save(
            DocumentVersion.builder()
                .document(document)
                .versionNo(versionNo)
                .titleSnapshot(document.getTitle())
                .status(status)
                .createdBy(owner)
                .build()
        );
    }

    private void saveJob(DocumentVersion version, EmbeddingJobStatus status) {
        EmbeddingModel model = embeddingModelRepository.findAllByIsActiveTrueAndIsSearchableTrue().get(0);
        embeddingJobRepository.save(
            EmbeddingJob.builder()
                .documentVersion(version)
                .embeddingModel(model)
                .status(status)
                .priority(0)
                .maxRetryCount(3)
                .build()
        );
    }

    private DocumentStatusProjection findSingleStatus(Long documentId) {
        List<DocumentStatusProjection> rows = documentRepository.findDocumentStatus(
            documentId,
            PROCESSING_VERSION_STATUSES,
            ACTIVE_JOB_STATUSES
        );
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
