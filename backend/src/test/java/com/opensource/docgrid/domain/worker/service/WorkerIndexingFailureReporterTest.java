package com.opensource.docgrid.domain.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.embedding.client.EmbeddingProviderException;
import com.opensource.docgrid.domain.embedding.dto.request.FailDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingFailureService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Worker 실패 Reporter가 고정된 분류 결과만 전달하고 소유권 상실은 보고하지 않는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerIndexingFailureReporter 테스트")
class WorkerIndexingFailureReporterTest {

    @Mock private DocumentIndexingFailureService failureService;

    @Test
    @DisplayName("분류된 유형과 안전한 메시지로 현재 Attempt 실패를 보고한다")
    void report_sendsClassifiedFailure() {
        WorkerIndexingFailureReporter reporter = new WorkerIndexingFailureReporter(
            new WorkerIndexingFailureClassifier(),
            failureService
        );
        ClaimedEmbeddingJobResponse claimedJob = claimedJob();
        ArgumentCaptor<FailDocumentIndexingRequest> requestCaptor =
            ArgumentCaptor.forClass(FailDocumentIndexingRequest.class);
        ArgumentCaptor<Duration> delayCaptor = ArgumentCaptor.forClass(Duration.class);

        reporter.report(
            claimedJob,
            100L,
            new DocGridException(ErrorCode.FILE_STORAGE_FAILED, "sensitive object key")
        );

        then(failureService).should().fail(
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq(100L),
            requestCaptor.capture(),
            delayCaptor.capture()
        );
        assertThat(requestCaptor.getValue().failureType())
            .isEqualTo(IndexingFailureType.STORAGE_UNAVAILABLE);
        assertThat(requestCaptor.getValue().errorMessage()).doesNotContain("sensitive");
        assertThat(requestCaptor.getValue().claimToken()).isEqualTo(claimedJob.claimToken());
        assertThat(delayCaptor.getValue()).isZero();
    }

    @Test
    @DisplayName("Provider Retry-After를 과부하 실패의 최소 Job 지연으로 전달한다")
    void report_forwardsProviderMinimumRetryDelay() {
        WorkerIndexingFailureReporter reporter = new WorkerIndexingFailureReporter(
            new WorkerIndexingFailureClassifier(),
            failureService
        );
        ArgumentCaptor<FailDocumentIndexingRequest> requestCaptor =
            ArgumentCaptor.forClass(FailDocumentIndexingRequest.class);
        ArgumentCaptor<Duration> delayCaptor = ArgumentCaptor.forClass(Duration.class);

        reporter.report(
            claimedJob(),
            100L,
            new EmbeddingProviderException(
                ErrorCode.EMBEDDING_PROVIDER_OVERLOADED,
                Duration.ofSeconds(15),
                true
            )
        );

        then(failureService).should().fail(
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq(100L),
            requestCaptor.capture(),
            delayCaptor.capture()
        );
        assertThat(requestCaptor.getValue().failureType())
            .isEqualTo(IndexingFailureType.EMBEDDING_PROVIDER_OVERLOADED);
        assertThat(delayCaptor.getValue()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("소유권을 잃었으면 실패 Service를 호출하지 않는다")
    void report_skipsOwnershipLostFailure() {
        WorkerIndexingFailureReporter reporter = new WorkerIndexingFailureReporter(
            new WorkerIndexingFailureClassifier(),
            failureService
        );

        reporter.report(
            claimedJob(),
            100L,
            new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID)
        );

        then(failureService).should(never()).fail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("실패 보고 자체가 실패해도 예외를 외부로 전파하지 않는다")
    void report_isolatesReportingFailure() {
        WorkerIndexingFailureReporter reporter = new WorkerIndexingFailureReporter(
            new WorkerIndexingFailureClassifier(),
            failureService
        );
        given(failureService.fail(any(), any(), any(), any()))
            .willThrow(new IllegalStateException("report failed"));

        reporter.report(claimedJob(), 100L, new IllegalStateException("pipeline failed"));

        then(failureService).should().fail(any(), any(), any(), any());
    }

    private ClaimedEmbeddingJobResponse claimedJob() {
        return new ClaimedEmbeddingJobResponse(
            10L,
            EmbeddingJobStatus.PROCESSING,
            1L,
            5L,
            7L,
            "34c19d16-6ae1-4f6a-a35d-0123456789ab",
            LocalDateTime.of(2026, 8, 3, 18, 0),
            LocalDateTime.of(2026, 8, 3, 18, 5)
        );
    }
}
