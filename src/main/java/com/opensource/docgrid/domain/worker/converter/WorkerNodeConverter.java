package com.opensource.docgrid.domain.worker.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.worker.dto.response.WorkerNodeResponse;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

@Component
public class WorkerNodeConverter {

    public WorkerNodeResponse toResponse(WorkerNode workerNode, WorkerStatus effectiveStatus) {
        return new WorkerNodeResponse(
            workerNode.getId(),
            workerNode.getWorkerName(),
            workerNode.getInstanceId(),
            workerNode.getHostName(),
            workerNode.getIpAddress(),
            effectiveStatus,
            workerNode.getLastHeartbeatAt(),
            workerNode.getStartedAt(),
            workerNode.getStoppedAt()
        );
    }
}
