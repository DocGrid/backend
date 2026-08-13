package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;

/**
 * INDEXED Vector 복구가 기존 Embedding을 삭제하지 않고 Version 상태와 새 Job만 원자 전환하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IndexedVersionVectorRepairService 단위 테스트")
class IndexedVersionVectorRepairServiceTest {

    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentChunkRepository documentChunkRepository;
    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingRepository embeddingRepository;

    private IndexedVersionVectorRepairService service;
    private Document document;
    private DocumentVersion version;
    private EmbeddingModel model;

    @BeforeEach
    void setUp() {
        service = new IndexedVersionVectorRepairService(
            documentVersionRepository,
            documentRepository,
            documentChunkRepository,
            embeddingJobRepository,
            embeddingRepository
        );
        document = Document.builder()
            .title("Vector 복구 문서")
            .documentType(DocumentType.PDF)
            .sourceType(DocumentSourceType.UPLOAD)
            .status(DocumentStatus.INDEXED)
            .visibility(VisibilityType.PRIVATE)
            .build();
        ReflectionTestUtils.setField(document, "id", 3L);
        version = DocumentVersion.builder()
            .document(document)
            .versionNo(1)
            .status(DocumentVersionStatus.INDEXED)
            .build();
        ReflectionTestUtils.setField(version, "id", 11L);
        document.updateCurrentVersion(version);
        model = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(model, "id", 7L);

        given(documentVersionRepository.findByIdForUpdate(11L)).willReturn(Optional.of(version));
        given(documentRepository.findByIdForUpdate(3L)).willReturn(Optional.of(document));
        given(documentChunkRepository.existsByDocumentVersionId(11L)).willReturn(true);
        given(documentChunkRepository.countByDocumentVersionId(11L)).willReturn(3L);
        given(embeddingJobRepository.existsByDocumentVersionIdAndStatusIn(any(), any())).willReturn(false);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(11L, 7L)).willReturn(1L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
            11L, 7L, EmbeddingStatus.ACTIVE
        )).willReturn(1L);
        given(embeddingJobRepository.save(any(EmbeddingJob.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("부분 누락은 기존 Vector를 보존한 채 CHUNKED와 PENDING Job으로 전환한다")
    void repair_preservesVectorsAndCreatesJob() {
        UUID sourceEventId = UUID.randomUUID();

        EmbeddingJob result = service.repair(11L, model, sourceEventId);

        assertThat(version.getStatus()).isEqualTo(DocumentVersionStatus.CHUNKED);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXING);
        assertThat(result.getStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
        assertThat(result.getSourceEventId()).isEqualTo(sourceEventId);
        then(embeddingRepository).should(never()).deleteByDocumentVersionId(any());
        ArgumentCaptor<EmbeddingJob> jobCaptor = ArgumentCaptor.forClass(EmbeddingJob.class);
        then(embeddingJobRepository).should().save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getDocumentVersion()).isSameAs(version);
    }
}
