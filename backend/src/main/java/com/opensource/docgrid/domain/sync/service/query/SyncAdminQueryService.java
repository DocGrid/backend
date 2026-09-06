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
 * 관리자 Dashboard용 Outbox 적체·처리 품질·정합성 Issue와 Reconciliation 실행 이력을 조회한다.
 *
 * <p>현재 Queue Snapshot과 최근 24시간 성공·실패를 읽기 전용으로 집계하고, Event·Issue 목록은
 * 고정 정렬과 페이지 응답으로 변환한다. Claim Token, 내부 Payload와 오류 메시지는 공개 DTO에서 제외하며
 * 상태 변경이나 복구 요청은 수행하지 않는다.
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

    /**
     * 현재 시각을 기준으로 Queue, Issue와 가장 최근 Reconciliation 실행 요약을 한 응답에 조립한다.
     */
    public SyncAdminSummaryResponse getSummary() {
        // 1. 모든 하위 집계가 같은 기준 시각과 최근 24시간 범위를 사용하도록 한 번만 계산한다.
        LocalDateTime capturedAt = LocalDateTime.now(clock);
        LocalDateTime since = capturedAt.minusHours(24);

        // 2. 독립 집계를 공개 Dashboard 응답으로 결합한다.
        return new SyncAdminSummaryResponse(
            capturedAt,
            eventSummary(capturedAt, since),
            issueSummary(since),
            reconciliationSummary()
        );
    }

    /**
     * 선택적 상태·종류 조건으로 Outbox Event를 최근 발생 순으로 페이지 조회한다.
     */
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

    /**
     * 선택적 상태·유형·심각도 조건으로 정합성 Issue를 최근 탐지 순으로 페이지 조회한다.
     */
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

    /**
     * 현재 Queue 개수와 최근 기간의 처리·실패·재시도 품질 지표를 집계한다.
     */
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

    /**
     * 활성 Issue와 최근 자동복구 성공·실패 수를 집계한다.
     */
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

    /**
     * 가장 최근 Reconciliation 실행을 조회하고 이력이 없으면 빈 요약으로 남긴다.
     */
    private SyncReconciliationSummaryResponse reconciliationSummary() {
        return syncReconciliationRunRepository.findTopByOrderByStartedAtDescIdDesc()
            .map(this::toReconciliationSummary)
            .orElse(null);
    }

    /**
     * 내부 실행 Entity에서 관리자에게 필요한 Cursor·집계·종결 정보만 추출한다.
     */
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

    /**
     * 가장 오래 대기 중인 Event의 발생 시각부터 현재까지 경과 초를 계산한다.
     */
    private Long oldestPendingAgeSeconds(LocalDateTime capturedAt) {
        return syncOutboxEventRepository.findOldestOccurredAtByStatus(SyncEventStatus.PENDING)
            .map(occurredAt -> Math.max(0L, Duration.between(occurredAt, capturedAt).toSeconds()))
            .orElse(null);
    }

    /**
     * 완료된 Event 중 성공 비율을 소수점 첫째 자리 백분율로 계산하고 표본이 없으면 null을 반환한다.
     */
    static Double successRate(long processedCount, long failedCount) {
        long completedCount = processedCount + failedCount;
        if (completedCount == 0) {
            return null;
        }
        return Math.round(processedCount * 1000.0 / completedCount) / 10.0;
    }
}
