package com.opensource.docgrid.domain.sync.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.sync.converter.SyncAdminConverter;
import com.opensource.docgrid.domain.sync.dto.response.SyncAdminActionResponse;
import com.opensource.docgrid.domain.sync.entity.SyncAdminAction;
import com.opensource.docgrid.domain.sync.entity.SyncConsistencyIssue;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncConsistencyIssueRepository;
import com.opensource.docgrid.domain.sync.service.SyncReconciliationOrchestrator;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 관리자 Issue 복구가 행 잠금·repairable 경계·Outbox·감사 Action을 하나의 명령으로 묶는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncAdminCommandService 단위 테스트")
class SyncAdminCommandServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 22, 30);

    @Mock private SyncEventManualRetryService syncEventManualRetryService;
    @Mock private SyncConsistencyIssueRepository syncConsistencyIssueRepository;
    @Mock private SyncEventWriter syncEventWriter;
    @Mock private SyncReconciliationOrchestrator syncReconciliationOrchestrator;
    @Mock private SyncAdminActionWriter syncAdminActionWriter;
    @Mock private SyncAdminConverter syncAdminConverter;
    @Mock private SyncAdminAction action;

    private SyncAdminCommandService service;
    private DocumentVersion version;
    private EmbeddingModel model;

    @BeforeEach
    void setUp() {
        service = new SyncAdminCommandService(
            syncEventManualRetryService,
            syncConsistencyIssueRepository,
            syncEventWriter,
            syncReconciliationOrchestrator,
            syncAdminActionWriter,
            syncAdminConverter,
            Clock.fixed(Instant.parse("2026-08-13T13:30:00Z"), ZoneId.of("Asia/Seoul"))
        );
        version = DocumentVersion.builder()
            .versionNo(1)
            .status(DocumentVersionStatus.UPLOADED)
            .build();
        ReflectionTestUtils.setField(version, "id", 11L);
        model = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(model, "id", 7L);
    }

    @Test
    @DisplayName("복구 가능한 OPEN Issue는 Dispatcher가 처리할 Outbox Event와 연결한다")
    void repairIssue_createsAuditedOutboxEvent() {
        SyncConsistencyIssue issue = issue(true);
        SyncOutboxEvent event = repairEvent();
        given(syncConsistencyIssueRepository.findByIdForUpdate(41L)).willReturn(Optional.of(issue));
        given(syncEventWriter.recordDocumentReindexRequested(any(), any(), any())).willReturn(event);
        given(syncAdminActionWriter.record(any(), any(), any(), any(), any(), any()))
            .willReturn(action);
        given(syncAdminConverter.toActionResponse(action)).willReturn(
            new SyncAdminActionResponse(UUID.randomUUID(), null, null, "1", 3L, NOW)
        );

        service.repairIssue(41L, 3L);

        assertThat(issue.getStatus()).isEqualTo(SyncConsistencyIssueStatus.REPAIRING);
        assertThat(issue.getRepairEventId()).isEqualTo(event.getEventId());
        then(syncAdminActionWriter).should().record(
            3L,
            com.opensource.docgrid.domain.sync.enums.SyncAdminActionType.ISSUE_REPAIR_REQUESTED,
            com.opensource.docgrid.domain.sync.enums.SyncAdminTargetType.CONSISTENCY_ISSUE,
            "41",
            null,
            "{\"repairEventId\":\"%s\"}".formatted(event.getEventId())
        );
    }

    @Test
    @DisplayName("보고 전용 Issue는 관리자가 강제로 복구할 수 없다")
    void repairIssue_rejectsReportOnlyIssue() {
        given(syncConsistencyIssueRepository.findByIdForUpdate(41L))
            .willReturn(Optional.of(issue(false)));

        assertThatThrownBy(() -> service.repairIssue(41L, 3L))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.SYNC_ISSUE_REPAIR_NOT_ALLOWED));
        then(syncEventWriter).shouldHaveNoInteractions();
    }

    private SyncConsistencyIssue issue(boolean repairable) {
        SyncConsistencyIssue issue = SyncConsistencyIssue.builder()
            .issueKey("MISSING_JOB:VERSION:11:MODEL:7")
            .issueType(SyncConsistencyIssueType.MISSING_JOB)
            .severity(SyncConsistencySeverity.ERROR)
            .documentVersion(version)
            .embeddingModel(model)
            .expectedJson("{}")
            .actualJson("{}")
            .repairable(repairable)
            .detectedAt(NOW)
            .build();
        ReflectionTestUtils.setField(issue, "id", 41L);
        return issue;
    }

    private SyncOutboxEvent repairEvent() {
        return SyncOutboxEvent.builder()
            .eventId(UUID.randomUUID())
            .idempotencyKey("admin-repair:41")
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
