package com.opensource.docgrid.domain.worker.lifecycle;

import static com.opensource.docgrid.domain.worker.config.WorkerExecutionConfig.WORKER_JOB_EXECUTOR;

import java.time.Clock;
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
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool.WorkerExecutionSlot;
import com.opensource.docgrid.domain.worker.service.WorkerIndexingPipeline;
import com.opensource.docgrid.global.exception.DocGridException;

import lombok.extern.slf4j.Slf4j;

/**
 * 등록된 Worker의 빈 실행 슬롯만큼 PENDING Job을 Claim해 제한 Executor에 제출한다.
 *
 * <p>실행 슬롯을 Claim 전에 확보하므로 로컬 처리 능력을 초과한 PROCESSING Job을 만들지 않는다. 빈 Claim,
 * 조회 오류와 종료 경쟁은 현재 Polling 주기 안에서 격리한다. 빈 Queue가 이어지면 DB Claim 시도만 지수
 * Backoff하고, Job을 Claim하면 기본 Polling 주기로 즉시 복구한다.
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
    private final WorkerPollingBackoff pollingBackoff;
    private final Clock clock;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public WorkerJobPollingScheduler(
        WorkerLifecycleManager workerLifecycleManager,
        EmbeddingJobClaimService claimService,
        WorkerExecutionSlotPool slotPool,
        @Qualifier(WORKER_JOB_EXECUTOR) ThreadPoolExecutor jobExecutor,
        WorkerIndexingPipeline indexingPipeline,
        IndexingWorkerProperties workerProperties,
        Clock clock
    ) {
        this.workerLifecycleManager = workerLifecycleManager;
        this.claimService = claimService;
        this.slotPool = slotPool;
        this.jobExecutor = jobExecutor;
        this.indexingPipeline = indexingPipeline;
        this.pollingBackoff = new WorkerPollingBackoff(
            workerProperties.getPollingInterval(),
            workerProperties.getIdleMaxPollingInterval()
        );
        this.clock = clock;
    }

    /**
     * 현재 가용 슬롯 수만큼 Job을 찾아 즉시 실행하고 첫 빈 Claim에서 주기를 끝낸다.
     */
    @Scheduled(
        fixedDelayString = "${indexing.worker.polling-interval:1s}",
        initialDelayString = "${indexing.worker.polling-interval:1s}"
    )
    public void poll() {
        if (!slotPool.isAccepting()
            || !pollingBackoff.isPollingDue(clock.instant())
            || !polling.compareAndSet(false, true)) {
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
                ClaimAttempt claimAttempt = claim(
                    registeredWorkerId.get(),
                    executionSlot
                );
                if (claimAttempt.failed()) {
                    return;
                }
                if (claimAttempt.claimedJob().isEmpty()) {
                    // 3. 빈 Queue에서만 DB 조회 간격을 늘리고 Claim 오류는 빠르게 재시도할 수 있게 둔다.
                    pollingBackoff.recordEmptyQueue(clock.instant());
                    return;
                }

                // 4. Job이 들어온 상태에서는 다음 기본 주기에 남은 Queue를 곧바로 확인한다.
                pollingBackoff.reset();
                ClaimedEmbeddingJobResponse claimedJob = claimAttempt.claimedJob().get();

                // 5. Queue 없이 즉시 실행하며 종료 경쟁으로 제출이 거부되면 Slot만 반환한다.
                if (!submit(claimedJob, executionSlot)) {
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

    private ClaimAttempt claim(
        Long workerId,
        WorkerExecutionSlot executionSlot
    ) {
        try {
            Optional<ClaimedEmbeddingJobResponse> claimedJob = claimService.claim(workerId);
            if (claimedJob.isEmpty()) {
                executionSlot.close();
            }
            return ClaimAttempt.completed(claimedJob);
        } catch (RuntimeException exception) {
            executionSlot.close();
            log.error(
                "Worker Job Polling 중 Claim에 실패했습니다. workerId={}, errorCode={}",
                workerId,
                diagnosticCode(exception)
            );
            return ClaimAttempt.failure();
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

    /** Claim 오류와 정상적인 빈 Queue를 구분해 Backoff 상태를 안전하게 갱신하는 내부 결과다. */
    private record ClaimAttempt(
        Optional<ClaimedEmbeddingJobResponse> claimedJob,
        boolean failed
    ) {

        private static ClaimAttempt completed(Optional<ClaimedEmbeddingJobResponse> claimedJob) {
            return new ClaimAttempt(claimedJob, false);
        }

        private static ClaimAttempt failure() {
            return new ClaimAttempt(Optional.empty(), true);
        }
    }
}
