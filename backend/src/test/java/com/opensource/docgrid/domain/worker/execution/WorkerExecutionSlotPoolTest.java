package com.opensource.docgrid.domain.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool.WorkerExecutionSlot;

/**
 * Worker 로컬 실행 슬롯의 최대 동시성, 단일 반환과 종료 후 획득 차단을 검증한다.
 */
@DisplayName("WorkerExecutionSlotPool 테스트")
class WorkerExecutionSlotPoolTest {

    @Test
    @DisplayName("설정된 수까지만 Slot을 획득하고 close 시 다시 사용할 수 있다")
    void tryAcquire_limitsConcurrencyAndReleasesSlot() {
        WorkerExecutionSlotPool slotPool = new WorkerExecutionSlotPool(2);
        WorkerExecutionSlot first = slotPool.tryAcquire().orElseThrow();
        WorkerExecutionSlot second = slotPool.tryAcquire().orElseThrow();

        assertThat(slotPool.getActiveSlots()).isEqualTo(2);
        assertThat(slotPool.tryAcquire()).isEmpty();

        first.close();
        assertThat(slotPool.getAvailableSlots()).isOne();
        assertThat(slotPool.tryAcquire()).isPresent();
        second.close();
    }

    @Test
    @DisplayName("같은 Slot을 여러 번 닫아도 Permit은 한 번만 반환한다")
    void close_releasesPermitOnlyOnce() {
        WorkerExecutionSlotPool slotPool = new WorkerExecutionSlotPool(1);
        WorkerExecutionSlot slot = slotPool.tryAcquire().orElseThrow();

        slot.close();
        slot.close();

        assertThat(slotPool.getAvailableSlots()).isOne();
        assertThat(slotPool.getActiveSlots()).isZero();
    }

    @Test
    @DisplayName("Polling 중단 뒤에는 남은 Permit이 있어도 신규 Slot을 주지 않는다")
    void stopAccepting_blocksNewSlots() {
        WorkerExecutionSlotPool slotPool = new WorkerExecutionSlotPool(1);

        slotPool.stopAccepting();

        assertThat(slotPool.isAccepting()).isFalse();
        assertThat(slotPool.tryAcquire()).isEmpty();
    }

    @Test
    @DisplayName("실행 슬롯 수는 1 이상이어야 한다")
    void constructor_rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new WorkerExecutionSlotPool(0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
