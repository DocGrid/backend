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

    /**
     * Job 실행 식별자와 비활성화 시 Manager Map에서 제거할 Callback을 보관한다.
     */
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

    /**
     * 권위 있는 Lease 갱신 거부를 한 번만 소유권 상실로 기록하고 예약을 취소한다.
     */
    void markOwnershipLost() {
        if (ownershipLost.compareAndSet(false, true)) {
            cancelScheduledTask();
            inactiveCallback.accept(this);
        }
    }

    /**
     * Pipeline이 Handle 수명을 종료했는지 반환한다.
     */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * 갱신 과정에서 Job 소유권을 확정적으로 잃었는지 반환한다.
     */
    boolean isOwnershipLost() {
        return ownershipLost.get();
    }

    /**
     * Lease를 갱신할 Job ID를 반환한다.
     */
    Long jobId() {
        return jobId;
    }

    /**
     * Claim을 소유한 Worker ID를 반환한다.
     */
    Long workerId() {
        return workerId;
    }

    /**
     * Lease 갱신 요청에만 사용할 Claim Token을 반환한다.
     */
    String claimToken() {
        return claimToken;
    }

    /**
     * Pipeline 종료 시 예약을 취소하고 Manager의 활성 Handle 목록에서 자신을 제거한다.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancelScheduledTask();
            inactiveCallback.accept(this);
        }
    }

    /**
     * 연결된 갱신 예약이 있으면 실행 중 Task를 강제 중단하지 않고 이후 실행만 취소한다.
     */
    private void cancelScheduledTask() {
        ScheduledFuture<?> future = scheduledFuture.get();
        if (future != null) {
            future.cancel(false);
        }
    }
}
