package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobConverter;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.domain.worker.repository.WorkerNodeRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class EmbeddingJobClaimService {

    private static final Set<WorkerStatus> CLAIMABLE_WORKER_STATUSES = EnumSet.of(
        WorkerStatus.ACTIVE,
        WorkerStatus.IDLE
    );
    private static final String LOCKED_EVENT_MESSAGE = "Worker가 Embedding Job을 Claim했습니다.";

    private final EmbeddingJobRepository embeddingJobRepository;
    private final WorkerNodeRepository workerNodeRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final EmbeddingJobConverter embeddingJobConverter;
    private final IndexingWorkerProperties indexingWorkerProperties;
    private final Clock clock;

    public Optional<ClaimedEmbeddingJobResponse> claim(Long workerId) {
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        WorkerNode workerNode = workerNodeRepository.findById(workerId)
            .orElseThrow(() -> new DocGridException(ErrorCode.WORKER_NOT_FOUND));
        validateClaimable(workerNode, claimedAt);

        return embeddingJobRepository.findNextPendingForUpdate()
            .map(job -> claim(job, workerNode, claimedAt));
    }

    private ClaimedEmbeddingJobResponse claim(
        EmbeddingJob embeddingJob,
        WorkerNode workerNode,
        LocalDateTime claimedAt
    ) {
        String claimToken = UUID.randomUUID().toString();
        LocalDateTime lockExpiresAt = claimedAt.plus(indexingWorkerProperties.getLeaseDuration());
        embeddingJob.claim(workerNode, claimToken, claimedAt, lockExpiresAt);

        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.LOCKED)
            .fromStatus(EmbeddingJobStatus.PENDING.name())
            .toStatus(EmbeddingJobStatus.PROCESSING.name())
            .message(LOCKED_EVENT_MESSAGE)
            .occurredAt(claimedAt)
            .build());

        return embeddingJobConverter.toClaimedResponse(embeddingJob);
    }

    private void validateClaimable(WorkerNode workerNode, LocalDateTime claimedAt) {
        LocalDateTime heartbeatDeadline = claimedAt.minus(indexingWorkerProperties.getDeadThreshold());
        WorkerStatus effectiveStatus = workerNode.resolveEffectiveStatus(heartbeatDeadline);
        if (!CLAIMABLE_WORKER_STATUSES.contains(effectiveStatus)) {
            throw new DocGridException(ErrorCode.WORKER_NOT_AVAILABLE);
        }
    }
}
