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

    /**
     * 공정한 순서로 Permit을 배분하는 고정 크기 실행 슬롯 Pool을 만든다.
     *
     * @param capacity 동시에 실행할 수 있는 최대 Job 수
     */
    public WorkerExecutionSlotPool(int capacity) {
        // 1. 슬롯이 없는 Worker가 기동되어 Polling만 반복하는 잘못된 설정을 즉시 거부한다.
        if (capacity < 1) {
            throw new IllegalArgumentException("Worker 실행 슬롯 수는 1 이상이어야 합니다.");
        }

        // 2. 대기 Thread 간 기아를 줄이기 위해 공정 모드 Semaphore를 사용한다.
        this.capacity = capacity;
        this.permits = new Semaphore(capacity, true);
    }

    /**
     * 현재 신규 실행을 허용하고 Permit이 남아 있을 때만 한 슬롯을 예약한다.
     */
    public Optional<WorkerExecutionSlot> tryAcquire() {
        // 1. 종료 중이거나 즉시 사용할 Permit이 없으면 비동기 Claim을 시작하지 않는다.
        if (!accepting.get() || !permits.tryAcquire()) {
            return Optional.empty();
        }

        // 2. Permit 획득과 종료 플래그 변경이 경쟁하면 Claim 전에 즉시 반환해 종료 이후 신규 작업을 막는다.
        if (!accepting.get()) {
            permits.release();
            return Optional.empty();
        }

        // 3. 호출자가 try-with-resources 또는 close로 Permit을 정확히 반환할 수 있는 Handle을 제공한다.
        return Optional.of(new WorkerExecutionSlot(this));
    }

    /**
     * 종료 절차가 시작된 뒤 신규 슬롯 획득을 영구적으로 중단한다.
     */
    public void stopAccepting() {
        accepting.set(false);
    }

    /**
     * 신규 실행 슬롯을 계속 받을 수 있는지 반환한다.
     */
    public boolean isAccepting() {
        return accepting.get();
    }

    /**
     * 이 Worker가 동시에 실행할 수 있는 전체 슬롯 수를 반환한다.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * 아직 예약되지 않은 슬롯 수를 반환한다.
     */
    public int getAvailableSlots() {
        return permits.availablePermits();
    }

    /**
     * 현재 예약되어 실행 중이거나 실행 준비 중인 슬롯 수를 반환한다.
     */
    public int getActiveSlots() {
        return capacity - permits.availablePermits();
    }

    /**
     * 수명 Handle이 소유한 Permit 하나를 Pool에 반환한다.
     */
    private void release() {
        permits.release();
    }

    /**
     * 한 번 획득한 Worker 실행 Permit을 정확히 한 번 반환하는 수명 Handle이다.
     */
    public static final class WorkerExecutionSlot implements AutoCloseable {

        private final WorkerExecutionSlotPool owner;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * 이 Handle이 반환할 Permit의 소유 Pool을 기억한다.
         */
        private WorkerExecutionSlot(WorkerExecutionSlotPool owner) {
            this.owner = owner;
        }

        /**
         * 소유한 Permit을 최초 호출에서만 반환해 중복 close로 슬롯 수가 늘어나는 것을 막는다.
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }
}
