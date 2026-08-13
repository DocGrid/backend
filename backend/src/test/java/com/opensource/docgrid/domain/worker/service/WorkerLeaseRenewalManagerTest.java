package com.opensource.docgrid.domain.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.embedding.dto.request.RenewEmbeddingJobLeaseRequest;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseService;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 실행별 Lease 갱신 예약, 소유권 상실, 일시 오류 유지와 전체 종료 동작을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerLeaseRenewalManager 테스트")
class WorkerLeaseRenewalManagerTest {

    @Mock private ScheduledThreadPoolExecutor leaseScheduler;
    @Mock private ScheduledFuture<Object> scheduledFuture;
    @Mock private EmbeddingJobLeaseService leaseService;

    private IndexingWorkerProperties workerProperties;
    private WorkerLeaseRenewalManager manager;
    private ArgumentCaptor<Runnable> taskCaptor;

    @BeforeEach
    void setUp() {
        workerProperties = new IndexingWorkerProperties();
        workerProperties.setLeaseRenewalInterval(Duration.ofSeconds(1));
        manager = new WorkerLeaseRenewalManager(leaseScheduler, leaseService, workerProperties);
        taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.doReturn(scheduledFuture).when(leaseScheduler).scheduleWithFixedDelay(
            taskCaptor.capture(),
            anyLong(),
            anyLong(),
            eq(TimeUnit.NANOSECONDS)
        );
    }

    @Test
    @DisplayName("설정 주기로 현재 Claim Lease를 갱신하고 close 시 예약을 제거한다")
    void start_schedulesRenewalAndCloseRemovesHandle() {
        ClaimedEmbeddingJobResponse claimedJob = claimedJob();
        WorkerLeaseRenewalHandle handle = manager.start(claimedJob);
        ArgumentCaptor<RenewEmbeddingJobLeaseRequest> requestCaptor =
            ArgumentCaptor.forClass(RenewEmbeddingJobLeaseRequest.class);

        taskCaptor.getValue().run();

        then(leaseService).should().renew(eq(10L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().workerId()).isEqualTo(1L);
        assertThat(requestCaptor.getValue().claimToken()).isEqualTo(claimedJob.claimToken());
        assertThat(manager.getActiveHandleCount()).isOne();

        handle.close();
        then(scheduledFuture).should().cancel(false);
        assertThat(manager.getActiveHandleCount()).isZero();
    }

    @Test
    @DisplayName("권위 있는 갱신 거부는 Handle을 lost로 전환한다")
    void renew_marksOwnershipLost_onOwnershipFailure() {
        given(leaseService.renew(any(), any()))
            .willThrow(new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID));
        WorkerLeaseRenewalHandle handle = manager.start(claimedJob());

        taskCaptor.getValue().run();

        assertThatThrownBy(handle::ensureOwned)
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID));
        assertThat(manager.getActiveHandleCount()).isZero();
        then(scheduledFuture).should().cancel(false);
    }

    @Test
    @DisplayName("일시 Runtime 오류는 Handle을 유지해 다음 갱신 기회를 남긴다")
    void renew_keepsHandle_onTransientRuntimeFailure() {
        given(leaseService.renew(any(), any()))
            .willThrow(new IllegalStateException("temporary database failure"));
        WorkerLeaseRenewalHandle handle = manager.start(claimedJob());

        taskCaptor.getValue().run();

        assertThatCode(handle::ensureOwned).doesNotThrowAnyException();
        assertThat(manager.getActiveHandleCount()).isOne();
        handle.close();
    }

    @Test
    @DisplayName("전체 종료는 활성 예약을 닫고 새 Handle 등록을 거부한다")
    void stopAll_closesHandlesAndRejectsNewOnes() {
        WorkerLeaseRenewalHandle handle = manager.start(claimedJob());

        manager.stopAll();

        assertThat(handle.isClosed()).isTrue();
        assertThat(manager.isAccepting()).isFalse();
        assertThat(manager.getActiveHandleCount()).isZero();
        assertThatThrownBy(() -> manager.start(claimedJob()))
            .isInstanceOf(IllegalStateException.class);
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
