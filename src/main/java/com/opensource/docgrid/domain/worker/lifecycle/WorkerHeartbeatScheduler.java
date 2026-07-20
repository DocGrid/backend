package com.opensource.docgrid.domain.worker.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.worker.service.command.WorkerNodeCommandService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")
public class WorkerHeartbeatScheduler {

    private final WorkerLifecycleManager workerLifecycleManager;
    private final WorkerNodeCommandService workerNodeCommandService;

    @Scheduled(
        fixedDelayString = "${indexing.worker.heartbeat-interval:10s}",
        initialDelayString = "${indexing.worker.heartbeat-interval:10s}"
    )
    public void updateHeartbeat() {
        workerLifecycleManager.getWorkerId().ifPresent(workerId -> {
            boolean updated = workerNodeCommandService.heartbeat(
                workerId,
                workerLifecycleManager.getInstanceId()
            );

            if (!updated) {
                log.error(
                    "인덱싱 Worker Heartbeat를 갱신하지 못했습니다. workerId={}, instanceId={}",
                    workerId,
                    workerLifecycleManager.getInstanceId()
                );
            }
        });
    }
}
