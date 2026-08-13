package com.opensource.docgrid.domain.worker.lifecycle;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.service.WorkerLeaseRenewalManager;

/**
 * Worker 종료 시 Polling 차단, 실행 유예, 강제 중단과 Lease Scheduler 정리 순서를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerExecutionLifecycleManager 테스트")
class WorkerExecutionLifecycleManagerTest {

    @Mock private WorkerJobPollingScheduler pollingScheduler;
    @Mock private ThreadPoolExecutor jobExecutor;
    @Mock private ScheduledThreadPoolExecutor leaseScheduler;
    @Mock private WorkerLeaseRenewalManager leaseRenewalManager;

    private WorkerExecutionLifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        IndexingWorkerProperties workerProperties = new IndexingWorkerProperties();
        workerProperties.setShutdownGracePeriod(Duration.ofSeconds(5));
        lifecycleManager = new WorkerExecutionLifecycleManager(
            pollingScheduler,
            jobExecutor,
            leaseScheduler,
            leaseRenewalManager,
            workerProperties
        );
    }

    @Test
    @DisplayName("실행이 유예 시간 안에 끝나면 interrupt 없이 Lease 자원을 정리한다")
    void shutdown_completesGracefully_whenExecutorTerminates() throws InterruptedException {
        given(jobExecutor.awaitTermination(anyLong(), eq(TimeUnit.NANOSECONDS)))
            .willReturn(true);

        lifecycleManager.shutdown();
        lifecycleManager.shutdown();

        then(pollingScheduler).should().stopPolling();
        then(jobExecutor).should().shutdown();
        then(jobExecutor).should().awaitTermination(
            Duration.ofSeconds(5).toNanos(),
            TimeUnit.NANOSECONDS
        );
        then(jobExecutor).should(never()).shutdownNow();
        then(leaseRenewalManager).should().stopAll();
        then(leaseScheduler).should().shutdown();
        then(pollingScheduler).should(times(1)).stopPolling();
    }

    @Test
    @DisplayName("유예 시간이 끝나면 남은 실행을 interrupt한 뒤 Lease 자원을 정리한다")
    void shutdown_interruptsJobs_whenGracePeriodExpires() throws InterruptedException {
        given(jobExecutor.awaitTermination(anyLong(), eq(TimeUnit.NANOSECONDS)))
            .willReturn(false);
        given(jobExecutor.shutdownNow()).willReturn(List.of(() -> {
        }));

        lifecycleManager.shutdown();

        then(pollingScheduler).should().stopPolling();
        then(jobExecutor).should().shutdown();
        then(jobExecutor).should().shutdownNow();
        then(leaseRenewalManager).should().stopAll();
        then(leaseScheduler).should().shutdown();
    }
}
