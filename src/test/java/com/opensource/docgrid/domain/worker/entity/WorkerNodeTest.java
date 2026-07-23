package com.opensource.docgrid.domain.worker.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.fixture.WorkerNodeFixture;

@DisplayName("WorkerNode 상태 판정 테스트")
class WorkerNodeTest {

    private static final LocalDateTime HEARTBEAT_DEADLINE = LocalDateTime.of(2026, 7, 20, 15, 0);

    @Test
    @DisplayName("DEAD 기준보다 최근 Heartbeat가 있으면 ACTIVE 상태를 유지한다")
    void resolveEffectiveStatus_returnsActive_when_heartbeatIsRecent() {
        WorkerNode workerNode = WorkerNodeFixture.createActiveWorker(HEARTBEAT_DEADLINE.plusSeconds(1));

        WorkerStatus result = workerNode.resolveEffectiveStatus(HEARTBEAT_DEADLINE);

        assertThat(result).isEqualTo(WorkerStatus.ACTIVE);
    }

    @Test
    @DisplayName("Heartbeat가 DEAD 기준 시각과 같으면 DEAD로 판정한다")
    void resolveEffectiveStatus_returnsDead_when_heartbeatMeetsDeadline() {
        WorkerNode workerNode = WorkerNodeFixture.createActiveWorker(HEARTBEAT_DEADLINE);

        WorkerStatus result = workerNode.resolveEffectiveStatus(HEARTBEAT_DEADLINE);

        assertThat(result).isEqualTo(WorkerStatus.DEAD);
    }

    @Test
    @DisplayName("Heartbeat가 없으면 DEAD로 판정한다")
    void resolveEffectiveStatus_returnsDead_when_heartbeatIsMissing() {
        WorkerNode workerNode = WorkerNodeFixture.createActiveWorker(null);

        WorkerStatus result = workerNode.resolveEffectiveStatus(HEARTBEAT_DEADLINE);

        assertThat(result).isEqualTo(WorkerStatus.DEAD);
    }

    @Test
    @DisplayName("STOPPED Worker는 Heartbeat가 오래되어도 STOPPED 상태를 유지한다")
    void resolveEffectiveStatus_returnsStopped_when_workerIsStopped() {
        WorkerNode workerNode = WorkerNodeFixture.createActiveWorker(HEARTBEAT_DEADLINE.minusMinutes(1));
        workerNode.markStopped(HEARTBEAT_DEADLINE.minusSeconds(1));

        WorkerStatus result = workerNode.resolveEffectiveStatus(HEARTBEAT_DEADLINE);

        assertThat(result).isEqualTo(WorkerStatus.STOPPED);
    }

    @Test
    @DisplayName("STOPPED Worker는 늦게 실행된 Heartbeat를 무시한다")
    void updateHeartbeat_doesNotUpdateStoppedWorker() {
        LocalDateTime originalHeartbeat = HEARTBEAT_DEADLINE.minusMinutes(1);
        WorkerNode workerNode = WorkerNodeFixture.createActiveWorker(originalHeartbeat);
        workerNode.markStopped(HEARTBEAT_DEADLINE.minusSeconds(1));

        workerNode.updateHeartbeat(HEARTBEAT_DEADLINE.plusSeconds(1));

        assertThat(workerNode.getLastHeartbeatAt()).isEqualTo(originalHeartbeat);
        assertThat(workerNode.getStatus()).isEqualTo(WorkerStatus.STOPPED);
    }

    @Test
    @DisplayName("ACTIVE와 IDLE Worker만 STOPPED 상태로 전환한다")
    void markStopped_stopsOnlyLiveWorkers() {
        WorkerNode activeWorker = createWorker(WorkerStatus.ACTIVE);
        WorkerNode idleWorker = createWorker(WorkerStatus.IDLE);

        activeWorker.markStopped(HEARTBEAT_DEADLINE);
        idleWorker.markStopped(HEARTBEAT_DEADLINE);

        assertThat(activeWorker.getStatus()).isEqualTo(WorkerStatus.STOPPED);
        assertThat(idleWorker.getStatus()).isEqualTo(WorkerStatus.STOPPED);
        assertThat(activeWorker.getStoppedAt()).isEqualTo(HEARTBEAT_DEADLINE);
        assertThat(idleWorker.getStoppedAt()).isEqualTo(HEARTBEAT_DEADLINE);
    }

    @Test
    @DisplayName("DEAD와 STOPPED Worker는 종료 요청으로 상태와 종료 시각을 덮어쓰지 않는다")
    void markStopped_preservesTerminalWorkers() {
        WorkerNode deadWorker = createWorker(WorkerStatus.DEAD);
        WorkerNode stoppedWorker = createWorker(WorkerStatus.ACTIVE);
        LocalDateTime originalStoppedAt = HEARTBEAT_DEADLINE.minusSeconds(1);
        stoppedWorker.markStopped(originalStoppedAt);

        deadWorker.markStopped(HEARTBEAT_DEADLINE);
        stoppedWorker.markStopped(HEARTBEAT_DEADLINE);

        assertThat(deadWorker.getStatus()).isEqualTo(WorkerStatus.DEAD);
        assertThat(deadWorker.getStoppedAt()).isNull();
        assertThat(stoppedWorker.getStatus()).isEqualTo(WorkerStatus.STOPPED);
        assertThat(stoppedWorker.getStoppedAt()).isEqualTo(originalStoppedAt);
    }

    @Test
    @DisplayName("ACTIVE와 IDLE Worker만 DEAD 상태로 전환한다")
    void markDead_marksOnlyLiveWorkersDead() {
        WorkerNode activeWorker = createWorker(WorkerStatus.ACTIVE);
        WorkerNode idleWorker = createWorker(WorkerStatus.IDLE);

        activeWorker.markDead();
        idleWorker.markDead();

        assertThat(activeWorker.getStatus()).isEqualTo(WorkerStatus.DEAD);
        assertThat(idleWorker.getStatus()).isEqualTo(WorkerStatus.DEAD);
    }

    @Test
    @DisplayName("DEAD와 STOPPED Worker는 DEAD 확정으로 상태를 덮어쓰지 않는다")
    void markDead_preservesTerminalWorkers() {
        WorkerNode deadWorker = createWorker(WorkerStatus.DEAD);
        WorkerNode stoppedWorker = createWorker(WorkerStatus.ACTIVE);
        LocalDateTime stoppedAt = HEARTBEAT_DEADLINE.minusSeconds(1);
        stoppedWorker.markStopped(stoppedAt);

        deadWorker.markDead();
        stoppedWorker.markDead();

        assertThat(deadWorker.getStatus()).isEqualTo(WorkerStatus.DEAD);
        assertThat(stoppedWorker.getStatus()).isEqualTo(WorkerStatus.STOPPED);
        assertThat(stoppedWorker.getStoppedAt()).isEqualTo(stoppedAt);
    }

    private WorkerNode createWorker(WorkerStatus status) {
        return WorkerNode.builder()
            .workerName(WorkerNodeFixture.WORKER_NAME)
            .instanceId(WorkerNodeFixture.INSTANCE_ID + "-" + status)
            .hostName(WorkerNodeFixture.HOST_NAME)
            .ipAddress(WorkerNodeFixture.IP_ADDRESS)
            .status(status)
            .lastHeartbeatAt(HEARTBEAT_DEADLINE)
            .startedAt(WorkerNodeFixture.STARTED_AT)
            .build();
    }
}
