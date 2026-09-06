package com.opensource.docgrid.domain.worker.lifecycle;

import static com.opensource.docgrid.domain.worker.config.WorkerExecutionConfig.WORKER_JOB_EXECUTOR;
import static com.opensource.docgrid.domain.worker.config.WorkerExecutionConfig.WORKER_LEASE_SCHEDULER;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.service.WorkerLeaseRenewalManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Worker Context 종료 시 신규 Claim, 활성 실행과 Lease 갱신을 순서대로 정리한다.
 *
 * <p>Worker DB 상태가 STOPPED로 바뀌기 전에 Job Executor가 스스로 끝날 기회를 보장한다. 유예 시간을
 * 넘긴 실행은 Thread interrupt와 Lease 갱신 취소만 수행하며 DB 상태는 만료 복구 정책에 맡긴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")
public class WorkerExecutionLifecycleManager {

    private final WorkerJobPollingScheduler pollingScheduler;
    private final ThreadPoolExecutor jobExecutor;
    private final ScheduledThreadPoolExecutor leaseScheduler;
    private final WorkerLeaseRenewalManager leaseRenewalManager;
    private final IndexingWorkerProperties workerProperties;
    private final AtomicBoolean closing = new AtomicBoolean(false);

    /**
     * Worker 실행과 Lease 갱신에 사용되는 두 Executor 및 종료 협력 객체를 연결한다.
     */
    public WorkerExecutionLifecycleManager(
        WorkerJobPollingScheduler pollingScheduler,
        @Qualifier(WORKER_JOB_EXECUTOR) ThreadPoolExecutor jobExecutor,
        @Qualifier(WORKER_LEASE_SCHEDULER) ScheduledThreadPoolExecutor leaseScheduler,
        WorkerLeaseRenewalManager leaseRenewalManager,
        IndexingWorkerProperties workerProperties
    ) {
        this.pollingScheduler = pollingScheduler;
        this.jobExecutor = jobExecutor;
        this.leaseScheduler = leaseScheduler;
        this.leaseRenewalManager = leaseRenewalManager;
        this.workerProperties = workerProperties;
    }

    /**
     * Worker STOPPED 기록보다 먼저 신규 Polling을 차단하고 활성 실행을 제한 시간 동안 기다린다.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ContextClosedEvent.class)
    public void shutdown() {
        // 1. 여러 종료 Event가 도착해도 정리 절차는 최초 호출에서만 수행한다.
        if (!closing.compareAndSet(false, true)) {
            return;
        }

        // 2. 신규 Slot과 Claim을 막은 뒤 이미 제출된 실행만 완료할 수 있게 Executor를 닫는다.
        pollingScheduler.stopPolling();
        jobExecutor.shutdown();

        // 3. 설정된 유예 시간 안에 모든 실행이 끝나면 interrupt 없이 Lease 자원만 정리한다.
        boolean terminated = awaitJobTermination(workerProperties.getShutdownGracePeriod());
        List<Runnable> cancelledTasks = List.of();
        if (!terminated) {
            // 4. 시간 초과 실행은 interrupt하고 DB 상태를 직접 변경하지 않아 Lease 복구가 회수하게 한다.
            cancelledTasks = jobExecutor.shutdownNow();
        }

        // 5. Job Thread 정리 뒤 남은 갱신 Handle과 Scheduler를 닫고 Worker STOPPED Listener에 제어를 넘긴다.
        leaseRenewalManager.stopAll();
        leaseScheduler.shutdown();
        log.info(
            "Worker 실행 종료를 완료했습니다. graceful={}, cancelledTaskCount={}, activeLeaseCount={}",
            terminated,
            cancelledTasks.size(),
            leaseRenewalManager.getActiveHandleCount()
        );
    }

    /**
     * 활성 Job 실행이 설정된 유예 시간 안에 자연 종료되는지 기다린다.
     *
     * <p>대기 Thread가 interrupt되면 interrupt 상태를 복원하고 강제 종료 경로를 선택한다.
     */
    private boolean awaitJobTermination(Duration gracePeriod) {
        try {
            return jobExecutor.awaitTermination(gracePeriod.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
