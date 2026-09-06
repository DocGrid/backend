package com.opensource.docgrid.domain.worker.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.worker.service.command.WorkerNodeCommandService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 등록된 현재 Worker 인스턴스의 생존 시각을 설정 주기로 갱신한다.
 *
 * <p>Worker 등록 전에는 DB 호출을 하지 않으며, 식별자 또는 instanceId가 더 이상 일치하지 않는 경우
 * 상태를 되돌리지 않고 운영 오류로 기록한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")
public class WorkerHeartbeatScheduler {

    private final WorkerLifecycleManager workerLifecycleManager;
    private final WorkerNodeCommandService workerNodeCommandService;

    /**
     * 현재 등록된 Worker가 있으면 instanceId를 포함한 조건부 Heartbeat 갱신을 요청한다.
     */
    @Scheduled(
        fixedDelayString = "${indexing.worker.heartbeat-interval:10s}",
        initialDelayString = "${indexing.worker.heartbeat-interval:10s}"
    )
    public void updateHeartbeat() {
        // 1. 애플리케이션 시작 중 아직 Worker가 등록되지 않았으면 이번 주기는 아무 작업 없이 끝낸다.
        workerLifecycleManager.getWorkerId().ifPresent(workerId -> {
            // 2. 현재 프로세스의 instanceId까지 대조해 과거 프로세스가 재등록 Worker를 갱신하지 못하게 한다.
            boolean updated = workerNodeCommandService.heartbeat(
                workerId,
                workerLifecycleManager.getInstanceId()
            );

            // 3. 대상이 없거나 종료 상태라면 상태를 강제로 복구하지 않고 운영 오류만 남긴다.
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
