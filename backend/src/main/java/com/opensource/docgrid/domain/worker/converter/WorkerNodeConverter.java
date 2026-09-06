package com.opensource.docgrid.domain.worker.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.worker.dto.response.WorkerNodeResponse;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

/**
 * WorkerNode Entity와 조회 시점에 계산한 실질 상태를 관리자 응답으로 변환한다.
 *
 * <p>Entity의 저장 상태보다 Heartbeat 만료 판단을 우선해 Scheduler 반영 전에도 정확한 운영 상태를 노출한다.
 */
@Component
public class WorkerNodeConverter {

    /** Worker 식별·호스트·생명주기 시각을 실질 상태와 함께 응답으로 평탄화한다. */
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
