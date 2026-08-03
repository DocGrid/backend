package com.opensource.docgrid.domain.worker.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("WorkerNodeRepository 테스트")
class WorkerNodeRepositoryTest {

    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 7, 20, 15, 0);

    @Autowired private WorkerNodeRepository workerNodeRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("같은 역할명의 Worker를 서로 다른 실행 인스턴스로 등록한다")
    void saveAll_allowsSameWorkerNameWithDifferentInstanceIds() {
        WorkerNode first = createWorker("instance-1", STARTED_AT);
        WorkerNode second = createWorker("instance-2", STARTED_AT.plusSeconds(1));

        workerNodeRepository.saveAllAndFlush(List.of(first, second));

        assertThat(workerNodeRepository.findAllByOrderByStartedAtDescIdDesc())
            .extracting(WorkerNode::getInstanceId)
            .containsSubsequence("instance-2", "instance-1");
    }

    @Test
    @DisplayName("동일한 instanceId를 가진 Worker는 중복 등록할 수 없다")
    void saveAndFlush_throws_when_instanceIdIsDuplicated() {
        workerNodeRepository.saveAndFlush(createWorker("duplicate-instance", STARTED_AT));

        assertThatThrownBy(() -> workerNodeRepository.saveAndFlush(
            createWorker("duplicate-instance", STARTED_AT.plusSeconds(1))
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ACTIVE Worker의 Heartbeat를 조건부 갱신한다")
    void updateHeartbeat_updatesActiveWorker() {
        WorkerNode workerNode = workerNodeRepository.saveAndFlush(createWorker("heartbeat-instance", STARTED_AT));
        LocalDateTime heartbeatAt = STARTED_AT.plusSeconds(10);

        int updatedRows = workerNodeRepository.updateHeartbeat(
            workerNode.getId(),
            workerNode.getInstanceId(),
            heartbeatAt,
            List.of(WorkerStatus.ACTIVE, WorkerStatus.IDLE)
        );
        flushAndClear();

        WorkerNode updatedWorker = workerNodeRepository.findById(workerNode.getId()).orElseThrow();
        assertThat(updatedRows).isEqualTo(1);
        assertThat(updatedWorker.getLastHeartbeatAt()).isEqualTo(heartbeatAt);
        assertThat(updatedWorker.getStatus()).isEqualTo(WorkerStatus.ACTIVE);
    }

    @Test
    @DisplayName("STOPPED Worker는 늦게 실행된 Heartbeat로 갱신되지 않는다")
    void updateHeartbeat_doesNotReviveStoppedWorker() {
        WorkerNode workerNode = workerNodeRepository.saveAndFlush(createWorker("stopped-instance", STARTED_AT));
        LocalDateTime stoppedAt = STARTED_AT.plusSeconds(5);
        workerNodeRepository.markStopped(
            workerNode.getId(),
            workerNode.getInstanceId(),
            stoppedAt,
            WorkerStatus.STOPPED,
            List.of(WorkerStatus.ACTIVE, WorkerStatus.IDLE)
        );

        int updatedRows = workerNodeRepository.updateHeartbeat(
            workerNode.getId(),
            workerNode.getInstanceId(),
            STARTED_AT.plusSeconds(10),
            List.of(WorkerStatus.ACTIVE, WorkerStatus.IDLE)
        );
        flushAndClear();

        WorkerNode stoppedWorker = workerNodeRepository.findById(workerNode.getId()).orElseThrow();
        assertThat(updatedRows).isZero();
        assertThat(stoppedWorker.getStatus()).isEqualTo(WorkerStatus.STOPPED);
        assertThat(stoppedWorker.getStoppedAt()).isEqualTo(stoppedAt);
        assertThat(stoppedWorker.getLastHeartbeatAt()).isEqualTo(STARTED_AT);
    }

    @Test
    @DisplayName("ACTIVE와 IDLE Worker만 STOPPED 상태로 갱신한다")
    void markStopped_updatesOnlyLiveWorkers() {
        WorkerNode activeWorker = createWorker("active-instance", STARTED_AT, WorkerStatus.ACTIVE);
        WorkerNode idleWorker = createWorker("idle-instance", STARTED_AT, WorkerStatus.IDLE);
        workerNodeRepository.saveAllAndFlush(List.of(activeWorker, idleWorker));
        LocalDateTime stoppedAt = STARTED_AT.plusSeconds(5);

        int activeUpdatedRows = workerNodeRepository.markStopped(
            activeWorker.getId(),
            activeWorker.getInstanceId(),
            stoppedAt,
            WorkerStatus.STOPPED,
            List.of(WorkerStatus.ACTIVE, WorkerStatus.IDLE)
        );
        int idleUpdatedRows = workerNodeRepository.markStopped(
            idleWorker.getId(),
            idleWorker.getInstanceId(),
            stoppedAt,
            WorkerStatus.STOPPED,
            List.of(WorkerStatus.ACTIVE, WorkerStatus.IDLE)
        );
        flushAndClear();

        assertThat(activeUpdatedRows).isEqualTo(1);
        assertThat(idleUpdatedRows).isEqualTo(1);
        assertThat(workerNodeRepository.findById(activeWorker.getId()).orElseThrow().getStatus())
            .isEqualTo(WorkerStatus.STOPPED);
        assertThat(workerNodeRepository.findById(idleWorker.getId()).orElseThrow().getStatus())
            .isEqualTo(WorkerStatus.STOPPED);
    }

    @Test
    @DisplayName("DEAD와 STOPPED Worker는 종료 요청으로 갱신하지 않는다")
    void markStopped_doesNotOverwriteTerminalWorkers() {
        WorkerNode deadWorker = createWorker("dead-instance", STARTED_AT, WorkerStatus.DEAD);
        WorkerNode stoppedWorker = createWorker("stopped-instance", STARTED_AT, WorkerStatus.ACTIVE);
        LocalDateTime originalStoppedAt = STARTED_AT.plusSeconds(5);
        stoppedWorker.markStopped(originalStoppedAt);
        workerNodeRepository.saveAllAndFlush(List.of(deadWorker, stoppedWorker));

        int deadUpdatedRows = workerNodeRepository.markStopped(
            deadWorker.getId(),
            deadWorker.getInstanceId(),
            STARTED_AT.plusSeconds(10),
            WorkerStatus.STOPPED,
            List.of(WorkerStatus.ACTIVE, WorkerStatus.IDLE)
        );
        int stoppedUpdatedRows = workerNodeRepository.markStopped(
            stoppedWorker.getId(),
            stoppedWorker.getInstanceId(),
            STARTED_AT.plusSeconds(10),
            WorkerStatus.STOPPED,
            List.of(WorkerStatus.ACTIVE, WorkerStatus.IDLE)
        );
        flushAndClear();

        WorkerNode persistedDeadWorker = workerNodeRepository.findById(deadWorker.getId()).orElseThrow();
        WorkerNode persistedStoppedWorker = workerNodeRepository.findById(stoppedWorker.getId()).orElseThrow();
        assertThat(deadUpdatedRows).isZero();
        assertThat(stoppedUpdatedRows).isZero();
        assertThat(persistedDeadWorker.getStatus()).isEqualTo(WorkerStatus.DEAD);
        assertThat(persistedDeadWorker.getStoppedAt()).isNull();
        assertThat(persistedStoppedWorker.getStatus()).isEqualTo(WorkerStatus.STOPPED);
        assertThat(persistedStoppedWorker.getStoppedAt()).isEqualTo(originalStoppedAt);
    }

    @Test
    @DisplayName("Heartbeat가 기준 시각 이하인 살아 있는 Worker만 DEAD로 확정한다")
    void markDeadWorkers_updatesOnlyExpiredLiveWorkers() {
        WorkerNode expiredActive = createWorker("expired-active", STARTED_AT, WorkerStatus.ACTIVE);
        WorkerNode expiredIdle = createWorker("expired-idle", STARTED_AT, WorkerStatus.IDLE);
        WorkerNode freshActive = createWorker(
            "fresh-active",
            STARTED_AT.plusSeconds(1),
            WorkerStatus.ACTIVE
        );
        WorkerNode stopped = createWorker("already-stopped", STARTED_AT, WorkerStatus.STOPPED);
        workerNodeRepository.saveAllAndFlush(List.of(
            expiredActive,
            expiredIdle,
            freshActive,
            stopped
        ));
        LocalDateTime detectedAt = STARTED_AT.plusSeconds(30);

        int updatedRows = workerNodeRepository.markDeadWorkers(
            detectedAt,
            STARTED_AT,
            WorkerStatus.DEAD,
            List.of(WorkerStatus.ACTIVE, WorkerStatus.IDLE)
        );
        flushAndClear();

        assertThat(updatedRows).isEqualTo(2);
        assertThat(workerNodeRepository.findById(expiredActive.getId()).orElseThrow().getStatus())
            .isEqualTo(WorkerStatus.DEAD);
        assertThat(workerNodeRepository.findById(expiredIdle.getId()).orElseThrow().getStatus())
            .isEqualTo(WorkerStatus.DEAD);
        assertThat(workerNodeRepository.findById(freshActive.getId()).orElseThrow().getStatus())
            .isEqualTo(WorkerStatus.ACTIVE);
        assertThat(workerNodeRepository.findById(stopped.getId()).orElseThrow().getStatus())
            .isEqualTo(WorkerStatus.STOPPED);
    }

    private WorkerNode createWorker(String instanceId, LocalDateTime startedAt) {
        return createWorker(instanceId, startedAt, WorkerStatus.ACTIVE);
    }

    private WorkerNode createWorker(String instanceId, LocalDateTime startedAt, WorkerStatus status) {
        return WorkerNode.builder()
            .workerName("indexing-worker")
            .instanceId(instanceId)
            .hostName("test-host")
            .ipAddress("127.0.0.1")
            .status(status)
            .lastHeartbeatAt(startedAt)
            .startedAt(startedAt)
            .build();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
