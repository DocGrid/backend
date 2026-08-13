package com.opensource.docgrid.domain.sync.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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

import com.opensource.docgrid.domain.sync.converter.SyncAdminConverter;
import com.opensource.docgrid.domain.sync.dto.response.SyncAdminSummaryResponse;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.entity.SyncReconciliationRun;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.repository.SyncConsistencyIssueRepository;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.repository.SyncReconciliationRunRepository;

/**
 * Sync 관리자 요약이 Queue 지연·24시간 성공률·Issue·마지막 실행 이력을 정확히 조합하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncAdminQueryService 단위 테스트")
class SyncAdminQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 22, 0);

    @Mock private SyncOutboxEventRepository syncOutboxEventRepository;
    @Mock private SyncConsistencyIssueRepository syncConsistencyIssueRepository;
    @Mock private SyncReconciliationRunRepository syncReconciliationRunRepository;
    @Mock private SyncAdminConverter syncAdminConverter;

    private SyncAdminQueryService service;

    @BeforeEach
    void setUp() {
        service = new SyncAdminQueryService(
            syncOutboxEventRepository,
            syncConsistencyIssueRepository,
            syncReconciliationRunRepository,
            syncAdminConverter,
            Clock.fixed(Instant.parse("2026-08-13T13:00:00Z"), ZoneId.of("Asia/Seoul"))
        );
    }

    @Test
    @DisplayName("가장 오래된 PENDING 지연과 처리 성공률 및 마지막 Event ID를 반환한다")
    void getSummary_aggregatesOperationalSnapshot() {
        LocalDateTime since = NOW.minusHours(24);
        SyncOutboxEvent lastProcessed = processedEvent();
        SyncReconciliationRun run = reconciliationRun();
        given(syncOutboxEventRepository.countByStatus(SyncEventStatus.PENDING)).willReturn(4L);
        given(syncOutboxEventRepository.countByStatus(SyncEventStatus.PROCESSING)).willReturn(2L);
        given(syncOutboxEventRepository.countByStatus(SyncEventStatus.FAILED)).willReturn(1L);
        given(syncOutboxEventRepository.findOldestOccurredAtByStatus(SyncEventStatus.PENDING))
            .willReturn(Optional.of(NOW.minusMinutes(5)));
        given(syncOutboxEventRepository.countByStatusAndProcessedAtGreaterThanEqual(
            SyncEventStatus.PROCESSED, since
        )).willReturn(9L);
        given(syncOutboxEventRepository.countByStatusAndUpdatedAtGreaterThanEqual(
            SyncEventStatus.FAILED, since
        )).willReturn(1L);
        given(syncOutboxEventRepository.countByUpdatedAtGreaterThanEqualAndRetryCountGreaterThan(since, 0))
            .willReturn(3L);
        given(syncOutboxEventRepository.findTopByStatusOrderByProcessedAtDescIdDesc(
            SyncEventStatus.PROCESSED
        )).willReturn(Optional.of(lastProcessed));
        given(syncConsistencyIssueRepository.countByStatus(SyncConsistencyIssueStatus.OPEN)).willReturn(2L);
        given(syncConsistencyIssueRepository.countByStatus(SyncConsistencyIssueStatus.REPAIRING)).willReturn(1L);
        given(syncConsistencyIssueRepository
            .countByStatusAndRepairEventIdIsNotNullAndResolvedAtGreaterThanEqual(
                SyncConsistencyIssueStatus.RESOLVED, since
            )).willReturn(5L);
        given(syncConsistencyIssueRepository.countFailedRepairIssues()).willReturn(1L);
        given(syncReconciliationRunRepository.findTopByOrderByStartedAtDescIdDesc())
            .willReturn(Optional.of(run));

        SyncAdminSummaryResponse result = service.getSummary();

        assertThat(result.capturedAt()).isEqualTo(NOW);
        assertThat(result.events().oldestPendingAgeSeconds()).isEqualTo(300L);
        assertThat(result.events().successRateLast24h()).isEqualTo(90.0);
        assertThat(result.events().lastProcessedEventId()).isEqualTo(lastProcessed.getEventId());
        assertThat(result.issues().openCount()).isEqualTo(2L);
        assertThat(result.issues().autoResolvedLast24hCount()).isEqualTo(5L);
        assertThat(result.reconciliation().runId()).isEqualTo(run.getRunId());
        assertThat(result.reconciliation().detectedCount()).isEqualTo(3);
    }

    private SyncOutboxEvent processedEvent() {
        SyncOutboxEvent event = SyncOutboxEvent.builder()
            .eventId(UUID.randomUUID())
            .idempotencyKey("summary:last-processed")
            .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
            .aggregateId(11L)
            .aggregateVersion(1L)
            .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
            .payloadJson("{}")
            .availableAt(NOW.minusMinutes(2))
            .occurredAt(NOW.minusMinutes(2))
            .maxRetryCount(5)
            .build();
        ReflectionTestUtils.setField(event, "status", SyncEventStatus.PROCESSED);
        ReflectionTestUtils.setField(event, "processedAt", NOW.minusMinutes(1));
        return event;
    }

    private SyncReconciliationRun reconciliationRun() {
        SyncReconciliationRun run = SyncReconciliationRun.builder()
            .runId(UUID.randomUUID())
            .mode(SyncReconciliationMode.REPAIR)
            .startCursor(0L)
            .startedAt(NOW.minusMinutes(3))
            .build();
        run.complete(100L, 100, 3, 2, NOW.minusMinutes(2));
        return run;
    }
}
