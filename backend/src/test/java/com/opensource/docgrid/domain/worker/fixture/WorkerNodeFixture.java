package com.opensource.docgrid.domain.worker.fixture;

import java.time.LocalDateTime;

import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

public class WorkerNodeFixture {

    public static final Long WORKER_ID = 1L;
    public static final String WORKER_NAME = "indexing-worker";
    public static final String INSTANCE_ID = "2f3a2d8c-1234-4abc-8def-123456789abc";
    public static final String HOST_NAME = "docgrid-api-01";
    public static final String IP_ADDRESS = "10.0.0.12";
    public static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 7, 20, 14, 59);

    private WorkerNodeFixture() {
    }

    public static WorkerNode createActiveWorker(LocalDateTime lastHeartbeatAt) {
        WorkerNode workerNode = WorkerNode.builder()
            .workerName(WORKER_NAME)
            .instanceId(INSTANCE_ID)
            .hostName(HOST_NAME)
            .ipAddress(IP_ADDRESS)
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(lastHeartbeatAt)
            .startedAt(STARTED_AT)
            .build();
        ReflectionTestUtils.setField(workerNode, "id", WORKER_ID);
        return workerNode;
    }
}
