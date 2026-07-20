package com.opensource.docgrid.domain.worker.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.WorkerNodeRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class WorkerNodeCommandService {

    private static final List<WorkerStatus> HEARTBEAT_STATUSES = List.of(
        WorkerStatus.ACTIVE,
        WorkerStatus.IDLE
    );

    private final WorkerNodeRepository workerNodeRepository;
    private final Clock clock;

    public Long register(String workerName, String instanceId, String hostName, String ipAddress) {
        LocalDateTime registeredAt = LocalDateTime.now(clock);
        WorkerNode workerNode = WorkerNode.builder()
            .workerName(workerName)
            .instanceId(instanceId)
            .hostName(hostName)
            .ipAddress(ipAddress)
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(registeredAt)
            .startedAt(registeredAt)
            .build();

        return workerNodeRepository.save(workerNode).getId();
    }

    public boolean heartbeat(Long workerId, String instanceId) {
        int updatedRows = workerNodeRepository.updateHeartbeat(
            workerId,
            instanceId,
            LocalDateTime.now(clock),
            HEARTBEAT_STATUSES
        );
        return updatedRows == 1;
    }

    public boolean stop(Long workerId, String instanceId) {
        int updatedRows = workerNodeRepository.markStopped(
            workerId,
            instanceId,
            LocalDateTime.now(clock),
            WorkerStatus.STOPPED
        );
        return updatedRows == 1;
    }
}
