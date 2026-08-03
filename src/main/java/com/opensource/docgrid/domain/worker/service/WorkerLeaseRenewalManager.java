package com.opensource.docgrid.domain.worker.service;

import static com.opensource.docgrid.domain.worker.config.WorkerExecutionConfig.WORKER_LEASE_SCHEDULER;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.embedding.dto.request.RenewEmbeddingJobLeaseRequest;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseService;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 활성 Worker Job별 Lease 갱신 Task를 등록하고 소유권 상실과 종료를 조정한다.
 *
 * <p>하나의 Scheduled Executor에서 짧은 갱신 Transaction만 실행한다. 권위 있는 소유권 오류는 해당
 * Handle을 lost로 전환하고, 일시적인 RuntimeException은 다음 갱신 주기와 단계별 DB fencing에 맡긴다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")
public class WorkerLeaseRenewalManager {

    private static final Set<ErrorCode> OWNERSHIP_LOST_ERRORS = EnumSet.of(
        ErrorCode.WORKER_NOT_FOUND,
        ErrorCode.WORKER_NOT_AVAILABLE,
        ErrorCode.EMBEDDING_JOB_NOT_FOUND,
        ErrorCode.EMBEDDING_JOB_NOT_PROCESSING,
        ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID,
        ErrorCode.EMBEDDING_JOB_LEASE_EXPIRED,
        ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT
    );

    private final ScheduledThreadPoolExecutor leaseScheduler;
    private final EmbeddingJobLeaseService leaseService;
    private final IndexingWorkerProperties workerProperties;
    private final ConcurrentHashMap<Long, WorkerLeaseRenewalHandle> activeHandles =
        new ConcurrentHashMap<>();

    public WorkerLeaseRenewalManager(
        @Qualifier(WORKER_LEASE_SCHEDULER) ScheduledThreadPoolExecutor leaseScheduler,
        EmbeddingJobLeaseService leaseService,
        IndexingWorkerProperties workerProperties
    ) {
        this.leaseScheduler = leaseScheduler;
        this.leaseService = leaseService;
        this.workerProperties = workerProperties;
    }

    /**
     * Attempt가 시작된 Claim의 Lease를 설정 주기로 갱신하는 Handle을 등록한다.
     */
    public WorkerLeaseRenewalHandle start(ClaimedEmbeddingJobResponse claimedJob) {
        WorkerLeaseRenewalHandle handle = new WorkerLeaseRenewalHandle(
            claimedJob.jobId(),
            claimedJob.workerId(),
            claimedJob.claimToken(),
            inactiveHandle -> activeHandles.remove(claimedJob.jobId(), inactiveHandle)
        );
        WorkerLeaseRenewalHandle existing = activeHandles.putIfAbsent(claimedJob.jobId(), handle);
        if (existing != null) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT);
        }

        try {
            Duration interval = workerProperties.getLeaseRenewalInterval();
            long intervalNanos = interval.toNanos();
            ScheduledFuture<?> future = leaseScheduler.scheduleWithFixedDelay(
                () -> renew(handle),
                intervalNanos,
                intervalNanos,
                TimeUnit.NANOSECONDS
            );
            handle.attach(future);
            return handle;
        } catch (RuntimeException exception) {
            handle.close();
            throw exception;
        }
    }

    /**
     * 애플리케이션 강제 종료 시 남은 모든 실행의 갱신 예약을 취소한다.
     */
    public void stopAll() {
        activeHandles.values().forEach(WorkerLeaseRenewalHandle::close);
    }

    public int getActiveHandleCount() {
        return activeHandles.size();
    }

    private void renew(WorkerLeaseRenewalHandle handle) {
        if (handle.isClosed() || handle.isOwnershipLost()) {
            return;
        }

        try {
            leaseService.renew(
                handle.jobId(),
                new RenewEmbeddingJobLeaseRequest(handle.workerId(), handle.claimToken())
            );
        } catch (DocGridException exception) {
            if (OWNERSHIP_LOST_ERRORS.contains(exception.getErrorCode())) {
                handle.markOwnershipLost();
                log.warn(
                    "활성 Worker Job Lease 소유권을 잃었습니다. workerId={}, jobId={}, errorCode={}",
                    handle.workerId(),
                    handle.jobId(),
                    exception.getErrorCode().getCode()
                );
                return;
            }
            log.error(
                "활성 Worker Job Lease를 갱신하지 못했습니다. workerId={}, jobId={}, errorCode={}",
                handle.workerId(),
                handle.jobId(),
                exception.getErrorCode().getCode()
            );
        } catch (RuntimeException exception) {
            log.error(
                "활성 Worker Job Lease 갱신 중 Runtime 오류가 발생했습니다. workerId={}, jobId={}, errorType={}",
                handle.workerId(),
                handle.jobId(),
                exception.getClass().getSimpleName()
            );
        }
    }
}
