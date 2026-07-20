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
}
