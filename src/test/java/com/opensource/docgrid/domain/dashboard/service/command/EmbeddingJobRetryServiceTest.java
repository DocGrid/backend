package com.opensource.docgrid.domain.dashboard.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.RetryAllJobsResponse;
import com.opensource.docgrid.domain.dashboard.service.query.DashboardQueryService;
import com.opensource.docgrid.domain.embedding.dto.response.ManualRetriedIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobManualRetryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingJobRetryService 단위 테스트")
class EmbeddingJobRetryServiceTest {

    @InjectMocks private EmbeddingJobRetryService embeddingJobRetryService;

    @Mock private EmbeddingJobManualRetryService embeddingJobManualRetryService;
    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private DashboardQueryService dashboardQueryService;
    @Mock private DashboardWebSocketController dashboardWebSocketController;

    @Test
    @DisplayName("정상 케이스: 단건 재처리 성공 시 A 서비스를 호출하고 최신 집계를 push한다")
    void retryJob_delegatesToAService_andPushesLatestSummary() {
        // Given
        ManualRetriedIndexingJobResponse retryResponse = mock(ManualRetriedIndexingJobResponse.class);
        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(embeddingJobManualRetryService.retry(42L)).willReturn(retryResponse);
        given(dashboardQueryService.getSummary()).willReturn(summary);

        // When
        ManualRetriedIndexingJobResponse result = embeddingJobRetryService.retryJob(42L);

        // Then
        assertThat(result).isSameAs(retryResponse);
        then(embeddingJobManualRetryService).should().retry(42L);
        then(dashboardWebSocketController).should().sendDashboardUpdate(summary);
    }

    @Test
    @DisplayName("정상 케이스: 개별 Job 실패는 건너뛰고 성공 건수만 집계하며 1건 이상 성공하면 push한다")
    void retryAllFailedJobs_countsOnlySuccesses_whenSomeJobsFail() {
        // Given
        EmbeddingJob succeedingJob1 = failedJobWithId(1L);
        EmbeddingJob failingJob = failedJobWithId(2L);
        EmbeddingJob succeedingJob2 = failedJobWithId(3L);
        given(embeddingJobRepository.findAllByStatus(EmbeddingJobStatus.FAILED))
            .willReturn(List.of(succeedingJob1, failingJob, succeedingJob2));

        willReturn(mock(ManualRetriedIndexingJobResponse.class))
            .given(embeddingJobManualRetryService).retry(1L);
        willThrow(new DocGridException(ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID))
            .given(embeddingJobManualRetryService).retry(2L);
        willReturn(mock(ManualRetriedIndexingJobResponse.class))
            .given(embeddingJobManualRetryService).retry(3L);

        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(dashboardQueryService.getSummary()).willReturn(summary);

        // When
        RetryAllJobsResponse result = embeddingJobRetryService.retryAllFailedJobs();

        // Then
        assertThat(result.retriedCount()).isEqualTo(2);
        assertThat(result.message()).isEqualTo("2개 작업 재처리 요청이 완료되었습니다.");
        then(embeddingJobManualRetryService).should().retry(1L);
        then(embeddingJobManualRetryService).should().retry(2L);
        then(embeddingJobManualRetryService).should().retry(3L);
        then(dashboardWebSocketController).should().sendDashboardUpdate(summary);
    }

    @Test
    @DisplayName("예외 케이스: FAILED 작업이 없으면 0건으로 정상 응답하고 push하지 않는다")
    void retryAllFailedJobs_returnsZero_whenNoFailedJobs() {
        // Given
        given(embeddingJobRepository.findAllByStatus(EmbeddingJobStatus.FAILED)).willReturn(List.of());

        // When
        RetryAllJobsResponse result = embeddingJobRetryService.retryAllFailedJobs();

        // Then
        assertThat(result.retriedCount()).isZero();
        assertThat(result.message()).isEqualTo("0개 작업 재처리 요청이 완료되었습니다.");
        then(dashboardWebSocketController).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: 모든 Job 재처리가 실패하면 push하지 않는다")
    void retryAllFailedJobs_doesNotPush_whenAllJobsFail() {
        // Given
        EmbeddingJob failingJob = failedJobWithId(1L);
        given(embeddingJobRepository.findAllByStatus(EmbeddingJobStatus.FAILED))
            .willReturn(List.of(failingJob));
        willThrow(new DocGridException(ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID))
            .given(embeddingJobManualRetryService).retry(1L);

        // When
        RetryAllJobsResponse result = embeddingJobRetryService.retryAllFailedJobs();

        // Then
        assertThat(result.retriedCount()).isZero();
        then(dashboardWebSocketController).shouldHaveNoInteractions();
    }

    private EmbeddingJob failedJobWithId(Long id) {
        EmbeddingJob embeddingJob = EmbeddingJob.builder()
            .status(EmbeddingJobStatus.FAILED)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", id);
        return embeddingJob;
    }
}
