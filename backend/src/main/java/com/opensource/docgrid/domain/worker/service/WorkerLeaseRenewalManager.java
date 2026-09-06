package com.opensource.docgrid.domain.worker.service;

import static com.opensource.docgrid.domain.worker.config.WorkerExecutionConfig.WORKER_LEASE_SCHEDULER;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    /**
     * Lease 갱신 전용 Scheduler와 짧은 DB 갱신 Service를 연결한다.
     */
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
        // 1. 종료가 시작된 Manager에는 새 갱신 Handle을 등록하지 않는다.
        if (!accepting.get()) {
            throw new IllegalStateException("Worker Lease 갱신 관리자가 종료 중입니다.");
        }

        WorkerLeaseRenewalHandle handle = new WorkerLeaseRenewalHandle(
            claimedJob.jobId(),
            claimedJob.workerId(),
            claimedJob.claimToken(),
            inactiveHandle -> activeHandles.remove(claimedJob.jobId(), inactiveHandle)
        );

        // 2. Job별 Handle을 하나만 허용해 같은 프로세스의 중복 갱신 Task를 차단한다.
        WorkerLeaseRenewalHandle existing = activeHandles.putIfAbsent(claimedJob.jobId(), handle);
        if (existing != null) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT);
        }
        if (!accepting.get()) {
            handle.close();
            throw new IllegalStateException("Worker Lease 갱신 관리자가 종료 중입니다.");
        }

        // 3. 설정 주기로 DB Lease를 갱신하고 예약 객체를 Handle에 연결해 수명을 함께 관리한다.
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
        // 1. 종료와 동시에 새 Handle 등록을 차단한다.
        accepting.set(false);

        // 2. Snapshot 성격의 동시 Map 순회를 통해 현재 보이는 모든 예약을 멱등 취소한다.
        activeHandles.values().forEach(WorkerLeaseRenewalHandle::close);
    }

    /**
     * 현재 갱신 예약을 보유한 활성 Job 수를 반환한다.
     */
    public int getActiveHandleCount() {
        return activeHandles.size();
    }

    /**
     * 새 Lease 갱신 Handle을 받을 수 있는지 반환한다.
     */
    public boolean isAccepting() {
        return accepting.get();
    }

    /**
     * Handle의 Job Lease를 한 번 갱신하고 오류 성격에 따라 소유권 상실 또는 일시 실패로 분류한다.
     *
     * <p>소유권을 확정적으로 잃은 오류만 Handle을 중단한다. 그 밖의 DB·Runtime 오류는 다음 예약과
     * 각 처리 단계의 DB fencing이 최종 판단할 수 있도록 기록 후 유지한다.
     */
    private void renew(WorkerLeaseRenewalHandle handle) {
        // 1. 닫혔거나 소유권 상실이 확정된 Handle에는 추가 DB 요청을 보내지 않는다.
        if (handle.isClosed() || handle.isOwnershipLost()) {
            return;
        }

        try {
            // 2. 현재 Worker와 Claim Token을 함께 보내 과거 Claim이 새 Lease를 연장하지 못하게 한다.
            leaseService.renew(
                handle.jobId(),
                new RenewEmbeddingJobLeaseRequest(handle.workerId(), handle.claimToken())
            );
        } catch (DocGridException exception) {
            // 3. 권위 있는 소유권 오류에는 갱신 예약을 영구 중단한다.
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

            // 4. 그 밖의 도메인 오류는 다음 주기 재시도와 처리 단계의 소유권 검증에 맡긴다.
            log.error(
                "활성 Worker Job Lease를 갱신하지 못했습니다. workerId={}, jobId={}, errorCode={}",
                handle.workerId(),
                handle.jobId(),
                exception.getErrorCode().getCode()
            );
        } catch (RuntimeException exception) {
            // 5. 인프라성 Runtime 오류도 Scheduler Thread를 중단하지 않고 다음 갱신 기회를 유지한다.
            log.error(
                "활성 Worker Job Lease 갱신 중 Runtime 오류가 발생했습니다. workerId={}, jobId={}, errorType={}",
                handle.workerId(),
                handle.jobId(),
                exception.getClass().getSimpleName()
            );
        }
    }
}
