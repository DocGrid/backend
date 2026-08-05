package com.opensource.docgrid.domain.worker.service;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 한 Worker Job 실행의 Lease 갱신 예약과 소유권 상태를 보관하는 수명 Handle이다.
 *
 * <p>Pipeline만 이 Handle을 닫으며 Scheduler는 권위 있는 갱신 거부가 발생하면 lost 상태로 전환한다.
 * Claim Token은 외부에 노출하지 않고 갱신 Task 내부 요청 생성에만 사용한다.
 */
public final class WorkerLeaseRenewalHandle implements AutoCloseable {

    private final Long jobId;
    private final Long workerId;
    private final String claimToken;
    private final Consumer<WorkerLeaseRenewalHandle> inactiveCallback;
    private final AtomicReference<ScheduledFuture<?>> scheduledFuture = new AtomicReference<>();
    private final AtomicBoolean ownershipLost = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    WorkerLeaseRenewalHandle(
        Long jobId,
        Long workerId,
        String claimToken,
        Consumer<WorkerLeaseRenewalHandle> inactiveCallback
    ) {
        this.jobId = jobId;
        this.workerId = workerId;
        this.claimToken = claimToken;
        this.inactiveCallback = inactiveCallback;
    }

    /**
     * Manager가 만든 주기 예약을 한 번만 연결한다.
     */
    void attach(ScheduledFuture<?> future) {
        if (!scheduledFuture.compareAndSet(null, future)) {
            future.cancel(false);
            throw new IllegalStateException("Lease 갱신 예약은 한 번만 연결할 수 있습니다.");
        }
        if (closed.get() || ownershipLost.get()) {
            future.cancel(false);
        }
    }

    /**
     * 단계 시작 전에 Scheduler가 확인한 소유권 상실을 Pipeline에 전달한다.
     */
    public void ensureOwned() {
        if (ownershipLost.get() || closed.get()) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID);
        }
    }

    void markOwnershipLost() {
        if (ownershipLost.compareAndSet(false, true)) {
            cancelScheduledTask();
            inactiveCallback.accept(this);
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    boolean isOwnershipLost() {
        return ownershipLost.get();
    }

    Long jobId() {
        return jobId;
    }

    Long workerId() {
        return workerId;
    }

    String claimToken() {
        return claimToken;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancelScheduledTask();
            inactiveCallback.accept(this);
        }
    }

    private void cancelScheduledTask() {
        ScheduledFuture<?> future = scheduledFuture.get();
        if (future != null) {
            future.cancel(false);
        }
    }
}
