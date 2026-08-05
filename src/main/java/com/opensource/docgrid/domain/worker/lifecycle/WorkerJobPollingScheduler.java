package com.opensource.docgrid.domain.worker.lifecycle;

import static com.opensource.docgrid.domain.worker.config.WorkerExecutionConfig.WORKER_JOB_EXECUTOR;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool.WorkerExecutionSlot;
import com.opensource.docgrid.domain.worker.service.WorkerIndexingPipeline;
import com.opensource.docgrid.global.exception.DocGridException;

import lombok.extern.slf4j.Slf4j;

/**
 * 등록된 Worker의 빈 실행 슬롯만큼 PENDING Job을 Claim해 제한 Executor에 제출한다.
 *
 * <p>실행 슬롯을 Claim 전에 확보하므로 로컬 처리 능력을 초과한 PROCESSING Job을 만들지 않는다. 빈 Claim,
 * 조회 오류와 종료 경쟁은 현재 Polling 주기 안에서 격리해 Spring Scheduler의 다음 실행을 유지한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")
public class WorkerJobPollingScheduler {

    private final WorkerLifecycleManager workerLifecycleManager;
    private final EmbeddingJobClaimService claimService;
    private final WorkerExecutionSlotPool slotPool;
    private final ThreadPoolExecutor jobExecutor;
    private final WorkerIndexingPipeline indexingPipeline;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public WorkerJobPollingScheduler(
        WorkerLifecycleManager workerLifecycleManager,
        EmbeddingJobClaimService claimService,
        WorkerExecutionSlotPool slotPool,
        @Qualifier(WORKER_JOB_EXECUTOR) ThreadPoolExecutor jobExecutor,
        WorkerIndexingPipeline indexingPipeline
    ) {
        this.workerLifecycleManager = workerLifecycleManager;
        this.claimService = claimService;
        this.slotPool = slotPool;
        this.jobExecutor = jobExecutor;
        this.indexingPipeline = indexingPipeline;
    }

    /**
     * 현재 가용 슬롯 수만큼 Job을 찾아 즉시 실행하고 첫 빈 Claim에서 주기를 끝낸다.
     */
    @Scheduled(
        fixedDelayString = "${indexing.worker.polling-interval:1s}",
        initialDelayString = "${indexing.worker.polling-interval:1s}"
    )
    public void poll() {
        if (!slotPool.isAccepting() || !polling.compareAndSet(false, true)) {
            return;
        }

        try {
            Optional<Long> registeredWorkerId = workerLifecycleManager.getWorkerId();
            if (registeredWorkerId.isEmpty()) {
                return;
            }

            // 1. 한 주기에는 설정된 전체 슬롯 수까지만 Claim을 시도하고 사용 중 슬롯은 즉시 건너뛴다.
            for (int index = 0; index < slotPool.getCapacity(); index++) {
                Optional<WorkerExecutionSlot> acquiredSlot = slotPool.tryAcquire();
                if (acquiredSlot.isEmpty()) {
                    return;
                }

                // 2. 슬롯 획득 뒤 시작된 종료와 Claim 오류는 이 Slot만 반환하고 현재 주기를 끝낸다.
                WorkerExecutionSlot executionSlot = acquiredSlot.get();
                if (!slotPool.isAccepting()) {
                    executionSlot.close();
                    return;
                }
                Optional<ClaimedEmbeddingJobResponse> claimedJob = claim(
                    registeredWorkerId.get(),
                    executionSlot
                );
                if (claimedJob.isEmpty()) {
                    return;
                }

                // 3. Queue 없이 즉시 실행하며 종료 경쟁으로 제출이 거부되면 Slot만 반환한다.
                if (!submit(claimedJob.get(), executionSlot)) {
                    return;
                }
            }
        } finally {
            polling.set(false);
        }
    }

    /**
     * 종료 Listener가 신규 Slot과 Claim을 중단한다.
     */
    public void stopPolling() {
        slotPool.stopAccepting();
    }

    public boolean isPollingEnabled() {
        return slotPool.isAccepting();
    }

    private Optional<ClaimedEmbeddingJobResponse> claim(
        Long workerId,
        WorkerExecutionSlot executionSlot
    ) {
        try {
            Optional<ClaimedEmbeddingJobResponse> claimedJob = claimService.claim(workerId);
            if (claimedJob.isEmpty()) {
                executionSlot.close();
            }
            return claimedJob;
        } catch (RuntimeException exception) {
            executionSlot.close();
            log.error(
                "Worker Job Polling 중 Claim에 실패했습니다. workerId={}, errorCode={}",
                workerId,
                diagnosticCode(exception)
            );
            return Optional.empty();
        }
    }

    private boolean submit(
        ClaimedEmbeddingJobResponse claimedJob,
        WorkerExecutionSlot executionSlot
    ) {
        try {
            jobExecutor.execute(() -> execute(claimedJob, executionSlot));
            return true;
        } catch (RejectedExecutionException exception) {
            executionSlot.close();
            log.warn(
                "종료 중 Worker Job 실행 제출이 거부됐습니다. workerId={}, jobId={}",
                claimedJob.workerId(),
                claimedJob.jobId()
            );
            return false;
        }
    }

    private void execute(
        ClaimedEmbeddingJobResponse claimedJob,
        WorkerExecutionSlot executionSlot
    ) {
        try {
            indexingPipeline.execute(claimedJob, executionSlot);
        } catch (RuntimeException exception) {
            // Attempt 시작 이전 오류는 실패 API로 합성하지 않고 Lease 만료 복구가 현재 Claim을 회수하게 한다.
            log.error(
                "Worker Job 실행을 시작하지 못했습니다. workerId={}, jobId={}, errorCode={}",
                claimedJob.workerId(),
                claimedJob.jobId(),
                diagnosticCode(exception)
            );
        }
    }

    private String diagnosticCode(RuntimeException exception) {
        if (exception instanceof DocGridException docGridException) {
            return docGridException.getErrorCode().getCode();
        }
        return exception.getClass().getSimpleName();
    }
}
