package com.opensource.docgrid.domain.worker.service.command;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.WorkerNodeRepository;

import lombok.RequiredArgsConstructor;

/**
 * Worker 등록, Heartbeat, 정상 종료와 Heartbeat 만료에 따른 DEAD 확정을 담당한다.
 *
 * <p>각 상태 변경은 Repository의 조건부 UPDATE로 늦게 도착한 생명주기 요청이 확정 상태를 되돌리지
 * 못하게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WorkerNodeCommandService {

    private static final List<WorkerStatus> LIVE_STATUSES = List.of(
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
            LIVE_STATUSES
        );
        return updatedRows == 1;
    }

    public boolean stop(Long workerId, String instanceId) {
        int updatedRows = workerNodeRepository.markStopped(
            workerId,
            instanceId,
            LocalDateTime.now(clock),
            WorkerStatus.STOPPED,
            LIVE_STATUSES
        );
        return updatedRows == 1;
    }

    /**
     * 마지막 Heartbeat가 DEAD 기준 시각에 도달한 살아 있는 Worker를 일괄 DEAD 확정한다.
     *
     * @param deadThreshold 마지막 Heartbeat 이후 DEAD로 판정할 시간
     * @return 이번 호출에서 DEAD로 전환된 Worker 수
     */
    public int markDeadWorkers(Duration deadThreshold) {
        // 1. 같은 실행에서 판정 시각과 Heartbeat 기준 시각이 흔들리지 않도록 현재 시각을 한 번만 구한다.
        LocalDateTime detectedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime heartbeatDeadline = detectedAt.minus(deadThreshold);

        // 2. 살아 있는 상태와 Heartbeat 경계를 UPDATE 조건에서 함께 검증해 경쟁 상태를 차단한다.
        return workerNodeRepository.markDeadWorkers(
            detectedAt,
            heartbeatDeadline,
            WorkerStatus.DEAD,
            LIVE_STATUSES
        );
    }
}
