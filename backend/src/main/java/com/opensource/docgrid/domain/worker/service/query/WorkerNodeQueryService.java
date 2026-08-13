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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkerNodeQueryService {

    private final WorkerNodeRepository workerNodeRepository;
    private final WorkerNodeConverter workerNodeConverter;
    private final IndexingWorkerProperties indexingWorkerProperties;
    private final Clock clock;

    public List<WorkerNodeResponse> getWorkers() {
        LocalDateTime heartbeatDeadline = LocalDateTime.now(clock)
            .minus(indexingWorkerProperties.getDeadThreshold());

        return workerNodeRepository.findAllByOrderByStartedAtDescIdDesc().stream()
            .map(workerNode -> workerNodeConverter.toResponse(
                workerNode,
                workerNode.resolveEffectiveStatus(heartbeatDeadline)
            ))
            .toList();
    }
}
