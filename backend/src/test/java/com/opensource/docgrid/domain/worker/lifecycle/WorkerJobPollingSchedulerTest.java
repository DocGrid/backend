package com.opensource.docgrid.domain.worker.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool.WorkerExecutionSlot;
import com.opensource.docgrid.domain.worker.service.WorkerIndexingPipeline;

/**
 * Worker Poller의 등록 조건, 빈 Queue Backoff, 실행 슬롯 제한과 오류 시 자원 반환을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerJobPollingScheduler 테스트")
class WorkerJobPollingSchedulerTest {

    @Mock private WorkerLifecycleManager workerLifecycleManager;
    @Mock private EmbeddingJobClaimService claimService;
    @Mock private ThreadPoolExecutor jobExecutor;
    @Mock private WorkerIndexingPipeline indexingPipeline;

    private WorkerExecutionSlotPool slotPool;
    private WorkerJobPollingScheduler scheduler;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        slotPool = new WorkerExecutionSlotPool(2);
        clock = new MutableClock(Instant.parse("2026-08-15T00:00:00Z"));
        scheduler = new WorkerJobPollingScheduler(
            workerLifecycleManager,
            claimService,
            slotPool,
            jobExecutor,
            indexingPipeline,
            new IndexingWorkerProperties(),
            clock
        );
    }

    @Test
    @DisplayName("Worker 등록 전에는 Job을 Claim하지 않는다")
    void poll_doesNotClaim_beforeWorkerRegistration() {
        given(workerLifecycleManager.getWorkerId()).willReturn(Optional.empty());

        scheduler.poll();

        then(claimService).shouldHaveNoInteractions();
        then(jobExecutor).shouldHaveNoInteractions();
        assertThat(slotPool.getAvailableSlots()).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 Claim은 획득한 Slot을 반환하고 현재 Polling을 끝낸다")
    void poll_releasesSlot_whenNoJobIsClaimed() {
        given(workerLifecycleManager.getWorkerId()).willReturn(Optional.of(1L));
        given(claimService.claim(1L)).willReturn(Optional.empty());

        scheduler.poll();

        then(claimService).should().claim(1L);
        then(jobExecutor).shouldHaveNoInteractions();
        assertThat(slotPool.getAvailableSlots()).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 Claim 뒤 Backoff 시간 전에는 DB Claim을 다시 호출하지 않는다")
    void poll_skipsClaim_beforeIdleBackoffExpires() {
        given(workerLifecycleManager.getWorkerId()).willReturn(Optional.of(1L));
        given(claimService.claim(1L)).willReturn(Optional.empty());

        scheduler.poll();
        clock.advanceSeconds(1);
        scheduler.poll();

        then(claimService).should().claim(1L);
        assertThat(slotPool.getAvailableSlots()).isEqualTo(2);
    }

    @Test
    @DisplayName("Claim 오류는 빈 Queue Backoff로 처리하지 않는다")
    void poll_retriesOnNextTick_afterClaimFailure() {
        given(workerLifecycleManager.getWorkerId()).willReturn(Optional.of(1L));
        given(claimService.claim(1L))
            .willThrow(new IllegalStateException("database unavailable"))
            .willReturn(Optional.empty());

        scheduler.poll();
        clock.advanceSeconds(1);
        scheduler.poll();

        then(claimService).should(times(2)).claim(1L);
        assertThat(slotPool.getAvailableSlots()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 Polling 주기에는 실행 가능한 Slot 수만큼만 Claim해 제출한다")
    void poll_claimsOnlyUpToAvailableCapacity() {
        List<Runnable> submittedTasks = new ArrayList<>();
        given(workerLifecycleManager.getWorkerId()).willReturn(Optional.of(1L));
        given(claimService.claim(1L))
            .willReturn(Optional.of(claimedJob(10L)))
            .willReturn(Optional.of(claimedJob(11L)));
        org.mockito.Mockito.doAnswer(invocation -> {
            submittedTasks.add(invocation.getArgument(0));
            return null;
        }).when(jobExecutor).execute(any(Runnable.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            WorkerExecutionSlot slot = invocation.getArgument(1);
            slot.close();
            return null;
        }).when(indexingPipeline).execute(
            any(ClaimedEmbeddingJobResponse.class),
            any(WorkerExecutionSlot.class)
        );

        scheduler.poll();

        then(claimService).should(times(2)).claim(1L);
        assertThat(submittedTasks).hasSize(2);
        assertThat(slotPool.getActiveSlots()).isEqualTo(2);

        submittedTasks.forEach(Runnable::run);

        then(indexingPipeline).should(times(2)).execute(
            any(ClaimedEmbeddingJobResponse.class),
            any(WorkerExecutionSlot.class)
        );
        assertThat(slotPool.getAvailableSlots()).isEqualTo(2);
    }

    @Test
    @DisplayName("Executor가 제출을 거부하면 Slot을 반환하고 추가 Claim을 중단한다")
    void poll_releasesSlot_whenSubmissionIsRejected() {
        given(workerLifecycleManager.getWorkerId()).willReturn(Optional.of(1L));
        given(claimService.claim(1L)).willReturn(Optional.of(claimedJob(10L)));
        org.mockito.Mockito.doThrow(new RejectedExecutionException("closing"))
            .when(jobExecutor).execute(any(Runnable.class));

        scheduler.poll();

        then(claimService).should().claim(1L);
        then(indexingPipeline).shouldHaveNoInteractions();
        assertThat(slotPool.getAvailableSlots()).isEqualTo(2);
    }

    @Test
    @DisplayName("종료 시작 후에는 신규 Polling과 Claim을 허용하지 않는다")
    void stopPolling_blocksFollowingPolls() {
        scheduler.stopPolling();

        scheduler.poll();

        assertThat(scheduler.isPollingEnabled()).isFalse();
        then(workerLifecycleManager).shouldHaveNoInteractions();
        then(claimService).shouldHaveNoInteractions();
    }

    private ClaimedEmbeddingJobResponse claimedJob(Long jobId) {
        return new ClaimedEmbeddingJobResponse(
            jobId,
            EmbeddingJobStatus.PROCESSING,
            1L,
            5L,
            7L,
            "34c19d16-6ae1-4f6a-a35d-0123456789ab",
            LocalDateTime.of(2026, 8, 3, 18, 0),
            LocalDateTime.of(2026, 8, 3, 18, 5)
        );
    }

    /** Test Clock whose time advances without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
