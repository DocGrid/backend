package com.opensource.docgrid.domain.worker.config;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool;

/**
 * Worker Job 실행과 Lease 갱신에 사용하는 제한된 Thread 자원을 구성한다.
 *
 * <p>Job Executor는 Queue에 Claim을 쌓지 않고 설정된 동시성만 즉시 실행한다. Lease Scheduler는 활성
 * 실행의 짧은 갱신 호출만 담당하며, Worker가 비활성화된 API 전용 실행에는 어떤 Thread도 만들지 않는다.
 */
@Configuration
@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")
public class WorkerExecutionConfig {

    public static final String WORKER_JOB_EXECUTOR = "workerJobExecutor";
    public static final String WORKER_LEASE_SCHEDULER = "workerLeaseScheduler";

    /**
     * 최대 동시 실행 수와 같은 크기의 무대기 Job Executor를 만든다.
     */
    @Bean(name = WORKER_JOB_EXECUTOR, destroyMethod = "shutdownNow")
    public ThreadPoolExecutor workerJobExecutor(IndexingWorkerProperties properties) {
        int maxConcurrency = properties.getMaxConcurrency();
        return new ThreadPoolExecutor(
            maxConcurrency,
            maxConcurrency,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            new CustomizableThreadFactory("indexing-worker-job-"),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 모든 활성 실행의 Lease 갱신을 직렬로 예약하는 단일 Thread Scheduler를 만든다.
     */
    @Bean(name = WORKER_LEASE_SCHEDULER, destroyMethod = "shutdownNow")
    public ScheduledThreadPoolExecutor workerLeaseScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
            1,
            new CustomizableThreadFactory("indexing-worker-lease-")
        );
        // 취소된 실행별 갱신 작업이 Scheduler Queue에 남아 종료와 메모리 회수를 늦추지 않게 한다.
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    /**
     * Claim 전에 실행 가능 여부를 예약하는 프로세스 로컬 슬롯 풀을 만든다.
     */
    @Bean
    public WorkerExecutionSlotPool workerExecutionSlotPool(IndexingWorkerProperties properties) {
        return new WorkerExecutionSlotPool(properties.getMaxConcurrency());
    }
}
