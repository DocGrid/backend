package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobConverter;
import com.opensource.docgrid.domain.embedding.dto.request.RenewEmbeddingJobLeaseRequest;
import com.opensource.docgrid.domain.embedding.dto.response.RenewedEmbeddingJobLeaseResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.WorkerNodeRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 유효한 현재 Worker Claim의 Embedding Job Lease 만료 시각을 갱신하는 Command Service.
 *
 * <p>Job → Worker 순서로 행을 잠그고 현재 소유권, 기존 Lease와 Worker 생존 상태를 같은 기준 시각으로
 * 검증한다. 기존 Worker·Claim Token·lockedAt은 바꾸지 않으며 갱신 Event와 외부 I/O를 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EmbeddingJobLeaseService {

    private static final Set<WorkerStatus> RENEWABLE_WORKER_STATUSES = EnumSet.of(
        WorkerStatus.ACTIVE,
        WorkerStatus.IDLE
    );

    private final EmbeddingJobRepository embeddingJobRepository;
    private final WorkerNodeRepository workerNodeRepository;
    private final EmbeddingJobOwnershipValidator ownershipValidator;
    private final EmbeddingJobConverter embeddingJobConverter;
    private final IndexingWorkerProperties workerProperties;
    private final Clock clock;

    /**
     * 현재 Claim의 Lease를 서버 설정 기간만큼 갱신한다.
     *
     * @param jobId 갱신할 Embedding Job 식별자
     * @param request 현재 Worker와 Claim Token
     * @return Claim Token을 제외한 갱신 결과
     */
    public RenewedEmbeddingJobLeaseResponse renew(
        Long jobId,
        RenewEmbeddingJobLeaseRequest request
    ) {
        // 1. DB TIMESTAMP 정밀도와 맞춘 하나의 기준 시각을 소유권, Heartbeat와 새 Lease 계산에 사용한다.
        LocalDateTime renewedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);

        // 2. 완료·실패·복구와 같은 직렬화 지점에서 현재 Job 소유권과 Lease를 먼저 검증한다.
        EmbeddingJob embeddingJob = embeddingJobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));
        ownershipValidator.validate(
            embeddingJob,
            request.workerId(),
            request.claimToken(),
            renewedAt
        );

        // 3. Worker 행을 다음에 잠가 DEAD·STOPPED 확정과 경쟁할 때 한 상태만 관찰한다.
        WorkerNode workerNode = workerNodeRepository.findByIdForUpdate(request.workerId())
            .orElseThrow(() -> new DocGridException(ErrorCode.WORKER_NOT_FOUND));
        validateRenewable(workerNode, renewedAt);

        // 4. 현재 Claim 세대는 유지하고 서버의 고정 Lease 기간으로 만료 시각만 연장한다.
        LocalDateTime renewedLockExpiresAt = renewedAt.plus(workerProperties.getLeaseDuration());
        embeddingJob.renewLease(renewedAt, renewedLockExpiresAt);
        return embeddingJobConverter.toRenewedLeaseResponse(embeddingJob, renewedAt);
    }

    /**
     * Worker의 저장 상태와 Heartbeat를 기준으로 현재 Claim의 Lease를 갱신할 수 있는지 검증한다.
     */
    private void validateRenewable(WorkerNode workerNode, LocalDateTime renewedAt) {
        // 1. 서버 설정의 DEAD 임계값을 현재 갱신 시각에 적용한다.
        LocalDateTime heartbeatDeadline = renewedAt.minus(workerProperties.getDeadThreshold());

        // 2. DB 상태 반영이 늦더라도 실질적으로 만료된 Worker의 Lease 연장을 거부한다.
        WorkerStatus effectiveStatus = workerNode.resolveEffectiveStatus(heartbeatDeadline);
        if (!RENEWABLE_WORKER_STATUSES.contains(effectiveStatus)) {
            throw new DocGridException(ErrorCode.WORKER_NOT_AVAILABLE);
        }
    }
}
