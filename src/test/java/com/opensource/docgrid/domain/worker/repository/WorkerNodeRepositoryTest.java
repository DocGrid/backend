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
            WorkerStatus.STOPPED
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

    private WorkerNode createWorker(String instanceId, LocalDateTime startedAt) {
        return WorkerNode.builder()
            .workerName("indexing-worker")
            .instanceId(instanceId)
            .hostName("test-host")
            .ipAddress("127.0.0.1")
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(startedAt)
            .startedAt(startedAt)
            .build();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
