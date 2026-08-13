package com.opensource.docgrid.domain.worker.execution;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 한 Worker 프로세스가 Claim할 수 있는 Job 수를 실제 실행 가능 슬롯으로 제한한다.
 *
 * <p>분산 소유권은 DB Claim이 담당하며 이 클래스는 로컬 최대 동시성만 관리한다. Poller는 슬롯을 먼저
 * 확보해야 Job을 Claim할 수 있고, 종료가 시작되면 남은 Permit과 관계없이 신규 획득을 거부한다.
 */
public class WorkerExecutionSlotPool {

    private final Semaphore permits;
    private final int capacity;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public WorkerExecutionSlotPool(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Worker 실행 슬롯 수는 1 이상이어야 합니다.");
        }
        this.capacity = capacity;
        this.permits = new Semaphore(capacity, true);
    }

    /**
     * 현재 신규 실행을 허용하고 Permit이 남아 있을 때만 한 슬롯을 예약한다.
     */
    public Optional<WorkerExecutionSlot> tryAcquire() {
        if (!accepting.get() || !permits.tryAcquire()) {
            return Optional.empty();
        }

        // Permit 획득과 종료 플래그 변경이 경쟁하면 Claim 전에 즉시 반환해 종료 이후 신규 작업을 막는다.
        if (!accepting.get()) {
            permits.release();
            return Optional.empty();
        }
        return Optional.of(new WorkerExecutionSlot(this));
    }

    /**
     * 종료 절차가 시작된 뒤 신규 슬롯 획득을 영구적으로 중단한다.
     */
    public void stopAccepting() {
        accepting.set(false);
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    public int getCapacity() {
        return capacity;
    }

    public int getAvailableSlots() {
        return permits.availablePermits();
    }

    public int getActiveSlots() {
        return capacity - permits.availablePermits();
    }

    private void release() {
        permits.release();
    }

    /**
     * 한 번 획득한 Worker 실행 Permit을 정확히 한 번 반환하는 수명 Handle이다.
     */
    public static final class WorkerExecutionSlot implements AutoCloseable {

        private final WorkerExecutionSlotPool owner;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private WorkerExecutionSlot(WorkerExecutionSlotPool owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }
}
