package com.opensource.docgrid.domain.sync.service.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobManualRetryService;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.service.SyncEventPayloadReader;

/**
 * 문서 버전 Event 재전달이 기존 Job을 재사용하고 누락된 경우에만 한 Job을 만드는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentVersionSyncEventHandler 단위 테스트")
class DocumentVersionSyncEventHandlerTest {

    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private EmbeddingModelRepository embeddingModelRepository;
    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingJobManualRetryService embeddingJobManualRetryService;

    private DocumentVersionSyncEventHandler handler;
    private DocumentVersion version;
    private EmbeddingModel model;
    private SyncOutboxEvent event;

    @BeforeEach
    void setUp() {
        handler = new DocumentVersionSyncEventHandler(
            documentVersionRepository,
            embeddingModelRepository,
            embeddingJobRepository,
            embeddingJobManualRetryService,
            new SyncEventPayloadReader(new ObjectMapper())
        );
        version = DocumentVersion.builder().versionNo(1).status(DocumentVersionStatus.UPLOADED).build();
        ReflectionTestUtils.setField(version, "id", 11L);
        model = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(model, "id", 7L);
        event = event();
        given(documentVersionRepository.findById(11L)).willReturn(Optional.of(version));
        given(embeddingModelRepository.findById(7L)).willReturn(Optional.of(model));
    }

    @Test
    @DisplayName("같은 source Event Job이 있으면 새 Job을 만들지 않는다")
    void handle_reusesExistingSourceJob() {
        EmbeddingJob existing = EmbeddingJob.builder()
            .documentVersion(version)
            .embeddingModel(model)
            .sourceEventId(event.getEventId())
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        given(embeddingJobRepository.findBySourceEventId(event.getEventId()))
            .willReturn(Optional.of(existing));

        handler.handle(event);

        then(embeddingJobRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("Job이 누락됐으면 source Event와 연결된 PENDING Job 하나를 생성한다")
    void handle_createsJob_whenSourceJobIsMissing() {
        given(embeddingJobRepository.findBySourceEventId(event.getEventId())).willReturn(Optional.empty());
        given(embeddingJobRepository.findTopByDocumentVersionIdAndEmbeddingModelIdOrderByIdDesc(11L, 7L))
            .willReturn(Optional.empty());

        handler.handle(event);

        ArgumentCaptor<EmbeddingJob> jobCaptor = ArgumentCaptor.forClass(EmbeddingJob.class);
        then(embeddingJobRepository).should().save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getSourceEventId()).isEqualTo(event.getEventId());
        assertThat(jobCaptor.getValue().getStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
        assertThat(jobCaptor.getValue().getDocumentVersion()).isSameAs(version);
        assertThat(jobCaptor.getValue().getEmbeddingModel()).isSameAs(model);
    }

    private SyncOutboxEvent event() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 19, 0);
        return SyncOutboxEvent.builder()
            .eventId(UUID.randomUUID())
            .idempotencyKey("version-created:11")
            .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
            .aggregateId(11L)
            .aggregateVersion(1L)
            .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
            .payloadJson("{\"embeddingModelId\":7}")
            .availableAt(now)
            .occurredAt(now)
            .maxRetryCount(5)
            .build();
    }
}
