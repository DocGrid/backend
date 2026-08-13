package com.opensource.docgrid.domain.sync.service.query;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.converter.SyncAdminConverter;
import com.opensource.docgrid.domain.sync.dto.response.SyncAdminSummaryResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncEventAdminResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncEventSummaryResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncIssueAdminResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncIssueSummaryResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncReconciliationSummaryResponse;
import com.opensource.docgrid.domain.sync.entity.SyncConsistencyIssue;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.entity.SyncReconciliationRun;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncConsistencyIssueRepository;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.repository.SyncReconciliationRunRepository;
import com.opensource.docgrid.global.common.response.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 Dashboard용 Outbox 적체·처리 품질·정합성 Issue와 실행 이력을 읽기 전용으로 집계한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SyncAdminQueryService {

    private static final Sort EVENT_SORT = Sort.by(
        Sort.Order.desc("occurredAt"),
        Sort.Order.desc("id")
    );
    private static final Sort ISSUE_SORT = Sort.by(
        Sort.Order.desc("lastDetectedAt"),
        Sort.Order.desc("id")
    );

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final SyncConsistencyIssueRepository syncConsistencyIssueRepository;
    private final SyncReconciliationRunRepository syncReconciliationRunRepository;
    private final SyncAdminConverter syncAdminConverter;
    private final Clock clock;

    public SyncAdminSummaryResponse getSummary() {
        LocalDateTime capturedAt = LocalDateTime.now(clock);
        LocalDateTime since = capturedAt.minusHours(24);
        return new SyncAdminSummaryResponse(
            capturedAt,
            eventSummary(capturedAt, since),
            issueSummary(since),
            reconciliationSummary()
        );
    }

    public PageResponse<SyncEventAdminResponse> getEvents(
        SyncEventStatus status,
        SyncEventType eventType,
        int page,
        int size
    ) {
        Page<SyncOutboxEvent> events = syncOutboxEventRepository.findAdminEvents(
            status,
            eventType,
            PageRequest.of(page, size, EVENT_SORT)
        );
        List<SyncEventAdminResponse> content = events.getContent().stream()
            .map(syncAdminConverter::toEventResponse)
            .toList();
        return PageResponse.from(events, content);
    }

    public PageResponse<SyncIssueAdminResponse> getIssues(
        SyncConsistencyIssueStatus status,
        SyncConsistencyIssueType issueType,
        SyncConsistencySeverity severity,
        int page,
        int size
    ) {
        Page<SyncConsistencyIssue> issues = syncConsistencyIssueRepository.findAdminIssues(
            status,
            issueType,
            severity,
            PageRequest.of(page, size, ISSUE_SORT)
        );
        List<SyncIssueAdminResponse> content = issues.getContent().stream()
            .map(syncAdminConverter::toIssueResponse)
            .toList();
        return PageResponse.from(issues, content);
    }

    private SyncEventSummaryResponse eventSummary(LocalDateTime capturedAt, LocalDateTime since) {
        long processedCount = syncOutboxEventRepository.countByStatusAndProcessedAtGreaterThanEqual(
            SyncEventStatus.PROCESSED,
            since
        );
        long failedCount = syncOutboxEventRepository.countByStatusAndUpdatedAtGreaterThanEqual(
            SyncEventStatus.FAILED,
            since
        );
        Optional<SyncOutboxEvent> lastProcessed = syncOutboxEventRepository
            .findTopByStatusOrderByProcessedAtDescIdDesc(SyncEventStatus.PROCESSED);
        return new SyncEventSummaryResponse(
            syncOutboxEventRepository.countByStatus(SyncEventStatus.PENDING),
            syncOutboxEventRepository.countByStatus(SyncEventStatus.PROCESSING),
            syncOutboxEventRepository.countByStatus(SyncEventStatus.FAILED),
            oldestPendingAgeSeconds(capturedAt),
            processedCount,
            failedCount,
            syncOutboxEventRepository.countByUpdatedAtGreaterThanEqualAndRetryCountGreaterThan(since, 0),
            successRate(processedCount, failedCount),
            lastProcessed.map(SyncOutboxEvent::getEventId).orElse(null),
            lastProcessed.map(SyncOutboxEvent::getProcessedAt).orElse(null)
        );
    }

    private SyncIssueSummaryResponse issueSummary(LocalDateTime since) {
        return new SyncIssueSummaryResponse(
            syncConsistencyIssueRepository.countByStatus(SyncConsistencyIssueStatus.OPEN),
            syncConsistencyIssueRepository.countByStatus(SyncConsistencyIssueStatus.REPAIRING),
            syncConsistencyIssueRepository
                .countByStatusAndRepairEventIdIsNotNullAndResolvedAtGreaterThanEqual(
                    SyncConsistencyIssueStatus.RESOLVED,
                    since
                ),
            syncConsistencyIssueRepository.countFailedRepairIssues()
        );
    }

    private SyncReconciliationSummaryResponse reconciliationSummary() {
        return syncReconciliationRunRepository.findTopByOrderByStartedAtDescIdDesc()
            .map(this::toReconciliationSummary)
            .orElse(null);
    }

    private SyncReconciliationSummaryResponse toReconciliationSummary(SyncReconciliationRun run) {
        return new SyncReconciliationSummaryResponse(
            run.getRunId(),
            run.getMode(),
            run.getStatus(),
            run.getStartCursor(),
            run.getEndCursor(),
            run.getScannedCount(),
            run.getDetectedCount(),
            run.getRepairRequestedCount(),
            run.getStartedAt(),
            run.getCompletedAt(),
            run.getErrorCode()
        );
    }

    private Long oldestPendingAgeSeconds(LocalDateTime capturedAt) {
        return syncOutboxEventRepository.findOldestOccurredAtByStatus(SyncEventStatus.PENDING)
            .map(occurredAt -> Math.max(0L, Duration.between(occurredAt, capturedAt).toSeconds()))
            .orElse(null);
    }

    private double successRate(long processedCount, long failedCount) {
        long completedCount = processedCount + failedCount;
        if (completedCount == 0) {
            return 100.0;
        }
        return Math.round(processedCount * 1000.0 / completedCount) / 10.0;
    }
}
