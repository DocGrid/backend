package com.opensource.docgrid.domain.worker.service.query;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.converter.WorkerNodeConverter;
import com.opensource.docgrid.domain.worker.dto.response.WorkerNodeResponse;
import com.opensource.docgrid.domain.worker.repository.WorkerNodeRepository;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 화면에 등록 Worker 목록과 Heartbeat를 반영한 실질 상태를 제공한다.
 *
 * <p>저장된 상태를 변경하지 않으며, DEAD 확정 Scheduler가 아직 실행되지 않은 구간도 같은 임계값으로
 * 계산해 만료 Worker가 ACTIVE 또는 IDLE로 노출되지 않게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkerNodeQueryService {

    private final WorkerNodeRepository workerNodeRepository;
    private final WorkerNodeConverter workerNodeConverter;
    private final IndexingWorkerProperties indexingWorkerProperties;
    private final Clock clock;

    /**
     * 최근 시작 Worker부터 조회해 현재 Heartbeat 기준의 상태가 포함된 응답으로 변환한다.
     */
    public List<WorkerNodeResponse> getWorkers() {
        // 1. 모든 Worker가 같은 생존 기준으로 평가되도록 DEAD 경계 시각을 한 번만 계산한다.
        LocalDateTime heartbeatDeadline = LocalDateTime.now(clock)
            .minus(indexingWorkerProperties.getDeadThreshold());

        // 2. 저장된 목록 순서를 유지하면서 각 Worker의 실질 상태를 응답에 반영한다.
        return workerNodeRepository.findAllByOrderByStartedAtDescIdDesc().stream()
            .map(workerNode -> workerNodeConverter.toResponse(
                workerNode,
                workerNode.resolveEffectiveStatus(heartbeatDeadline)
            ))
            .toList();
    }
}
