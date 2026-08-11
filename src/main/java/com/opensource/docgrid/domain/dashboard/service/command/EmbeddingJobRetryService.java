package com.opensource.docgrid.domain.dashboard.service.command;

import java.util.List;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.RetryAllJobsResponse;
import com.opensource.docgrid.domain.dashboard.service.query.DashboardQueryService;
import com.opensource.docgrid.domain.embedding.dto.response.ManualRetriedIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobManualRetryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 재처리 버튼 클릭을 A 담당자의 {@link EmbeddingJobManualRetryService}로 위임하고,
 * 성공 시 최신 대시보드 집계를 WebSocket으로 push하는 Command Service.
 *
 * <p>{@code embedding_jobs}를 직접 update하지 않는다 — 상태 전환은 전부 A의 Service를 경유한다.
 * FAILED 목록 조회만 {@link EmbeddingJobRepository}를 직접 읽는다(쓰기가 아니므로 A/B 경계 위반이
 * 아니다).
 *
 * <p>다른 Command Service와 달리 클래스 레벨 {@code @Transactional}을 의도적으로 두지 않는다.
 * {@link #retryAllFailedJobs()}가 여러 Job에 대해 {@code retry()}를 순회 호출하는데, 이 메서드
 * 자체가 트랜잭션을 열면 각 호출이 그 트랜잭션에 참여(REQUIRED)하게 되어, 중간에 한 Job이 실패해
 * 예외가 전파되는 순간 스프링이 트랜잭션을 rollback-only로 표시한다. 그러면 이후 catch로 예외를
 * 잡아도 커밋 시점에 이전에 성공한 Job들까지 전부 롤백된다 — "개별 실패는 건너뛰고 성공 건수만
 * 집계한다"는 요구사항 자체가 깨진다. 이 클래스가 트랜잭션을 열지 않아야 {@code retry()} 호출마다
 * A Service 자신의 {@code @Transactional}이 매번 독립된 새 트랜잭션을 만들어 각자 커밋된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingJobRetryService {

    private static final String RETRY_ALL_MESSAGE_FORMAT = "%d개 작업 재처리 요청이 완료되었습니다.";

    private final EmbeddingJobManualRetryService embeddingJobManualRetryService;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final DashboardQueryService dashboardQueryService;
    private final DashboardWebSocketController dashboardWebSocketController;

    // 관리자 FAILED Job 단건 재처리
    public ManualRetriedIndexingJobResponse retryJob(Long jobId) {
        ManualRetriedIndexingJobResponse response = embeddingJobManualRetryService.retry(jobId);
        dashboardWebSocketController.sendDashboardUpdate(dashboardQueryService.getSummary());
        return response;
    }

    // 관리자 FAILED Job 전체 재처리
    public RetryAllJobsResponse retryAllFailedJobs() {
        List<EmbeddingJob> failedJobs = embeddingJobRepository.findAllByStatus(EmbeddingJobStatus.FAILED);

        int retriedCount = 0;
        for (EmbeddingJob failedJob : failedJobs) {
            try {
                embeddingJobManualRetryService.retry(failedJob.getId());
                retriedCount++;
            } catch (Exception e) {
                log.warn("전체 재처리 중 Job 건너뜀: jobId={}, reason={}", failedJob.getId(), e.getMessage());
            }
        }

        if (retriedCount > 0) {
            dashboardWebSocketController.sendDashboardUpdate(dashboardQueryService.getSummary());
        }

        return new RetryAllJobsResponse(retriedCount, RETRY_ALL_MESSAGE_FORMAT.formatted(retriedCount));
    }
}
