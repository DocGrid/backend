package com.opensource.docgrid.domain.sync.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.domain.sync.config.SyncReconciliationProperties;
import com.opensource.docgrid.domain.sync.dto.SyncReconciliationBatchResult;
import com.opensource.docgrid.domain.sync.entity.SyncConsistencyIssue;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.service.SyncConsistencyInspector;
import com.opensource.docgrid.domain.sync.service.SyncConsistencyObservation;
import com.opensource.docgrid.domain.sync.service.SyncOrphanInspector;

/**
 * Cursor Batch가 반복 Issue를 수렴시키고 REPAIR 모드에서만 한 Outbox 복구 요청을 만드는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncReconciliationBatchService 단위 테스트")
class SyncReconciliationBatchServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 21, 0);

    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private EmbeddingModelQueryService embeddingModelQueryService;
    @Mock private SyncConsistencyInspector syncConsistencyInspector;
    @Mock private SyncConsistencyIssueService syncConsistencyIssueService;
    @Mock private SyncOrphanInspector syncOrphanInspector;
    @Mock private SyncEventWriter syncEventWriter;

    private SyncReconciliationBatchService service;
    private DocumentVersion version;
    private EmbeddingModel model;
    private SyncConsistencyObservation observation;
    private SyncConsistencyIssue issue;

    @BeforeEach
    void setUp() {
        SyncReconciliationProperties properties = new SyncReconciliationProperties();
        properties.setBatchSize(100);
        service = new SyncReconciliationBatchService(
            documentVersionRepository,
            embeddingModelQueryService,
            syncConsistencyInspector,
            syncConsistencyIssueService,
            syncOrphanInspector,
            syncEventWriter,
            properties,
            Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneId.of("Asia/Seoul"))
        );
        version = DocumentVersion.builder()
            .versionNo(1)
            .status(DocumentVersionStatus.UPLOADED)
            .build();
        ReflectionTestUtils.setField(version, "id", 11L);
        model = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(model, "id", 7L);
        observation = new SyncConsistencyObservation(
            "MISSING_JOB:VERSION:11:MODEL:7",
            SyncConsistencyIssueType.MISSING_JOB,
            SyncConsistencySeverity.ERROR,
            null,
            version,
            model,
            "{\"liveJob\":true}",
            "{\"liveJob\":false}",
            true
        );
        issue = SyncConsistencyIssue.builder()
            .issueKey(observation.issueKey())
            .issueType(observation.issueType())
            .severity(observation.severity())
            .documentVersion(version)
            .embeddingModel(model)
            .expectedJson(observation.expectedJson())
            .actualJson(observation.actualJson())
            .detectedAt(NOW)
            .build();
        given(embeddingModelQueryService.getActiveModel()).willReturn(model);
        given(documentVersionRepository.findReconciliationBatchAfterId(any(Long.class), any(Pageable.class)))
            .willReturn(List.of(version));
        given(syncConsistencyInspector.inspect(version, model, NOW)).willReturn(List.of(observation));
        given(syncConsistencyIssueService.detect(observation, NOW)).willReturn(issue);
    }

    @Test
    @DisplayName("REPAIR 모드는 안전한 OPEN Issue에 Outbox Event 하나를 연결한다")
    void reconcile_requestsSingleRepairEvent() {
        UUID runId = UUID.randomUUID();
        SyncOutboxEvent event = repairEvent();
        given(syncOrphanInspector.inspect()).willReturn(List.of());
        given(syncEventWriter.recordDocumentReindexRequested(any(), any(), any()))
            .willReturn(event);

        SyncReconciliationBatchResult result = service.reconcile(runId, 0L, SyncReconciliationMode.REPAIR);

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.detectedCount()).isEqualTo(1);
        assertThat(result.repairRequestedCount()).isEqualTo(1);
        assertThat(issue.getStatus()).isEqualTo(SyncConsistencyIssueStatus.REPAIRING);
        assertThat(issue.getRepairEventId()).isEqualTo(event.getEventId());
    }

    @Test
    @DisplayName("DRY_RUN은 Issue만 기록하고 복구 Event를 만들지 않는다")
    void reconcile_dryRunDoesNotCreateRepairEvent() {
        SyncReconciliationBatchResult result = service.reconcile(
            UUID.randomUUID(),
            1L,
            SyncReconciliationMode.DRY_RUN
        );

        assertThat(result.detectedCount()).isEqualTo(1);
        assertThat(result.repairRequestedCount()).isZero();
        assertThat(issue.getStatus()).isEqualTo(SyncConsistencyIssueStatus.OPEN);
        then(syncEventWriter).should(never()).recordDocumentReindexRequested(any(), any(), any());
    }

    private SyncOutboxEvent repairEvent() {
        return SyncOutboxEvent.builder()
            .eventId(UUID.randomUUID())
            .idempotencyKey("repair:11")
            .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
            .aggregateId(11L)
            .aggregateVersion(1L)
            .eventType(SyncEventType.DOCUMENT_REINDEX_REQUESTED)
            .payloadJson("{\"embeddingModelId\":7}")
            .availableAt(NOW)
            .occurredAt(NOW)
            .maxRetryCount(5)
            .build();
    }
}
