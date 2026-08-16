package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 만료된 Embedding Job Lease 후보 한 건을 독립 Transaction에서 재검증하고 복구하는 Command Service.
 *
 * <p>Job 행 잠금이 복구, 갱신, 완료와 협력적 실패의 단일 직렬화 지점이다. 현재 Claim의 STARTED
 * Attempt가 있으면 함께 실패시키고, 없으면 가짜 실행 이력을 만들지 않는다. Retry와 최종 실패 정책은
 * {@link IndexingFailureTransitionService}에 위임하며 외부 I/O를 수행하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class EmbeddingJobLeaseRecoveryService {

    private static final String LEASE_EXPIRED_FAILURE_CODE = "WORKER_LEASE_EXPIRED";
    private static final String LEASE_EXPIRED_MESSAGE =
        "Embedding Job Lease가 만료되어 현재 실행을 회수했습니다.";

    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final IndexingFailureTransitionService failureTransitionService;

    /**
     * 후보 Snapshot 이후에도 만료 상태인 Job만 회수해 Retry 또는 최종 실패로 전환한다.
     *
     * @param jobId 복구 후보 Embedding Job 식별자
     * @param recoveredAt 이번 Scheduler 실행의 복구 기준 시각
     * @return 실제 복구 여부와 복구 후 Job 상태
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecoveryResult recover(Long jobId, LocalDateTime recoveredAt) {
        validateRequest(jobId, recoveredAt);

        // 1. 갱신·완료·실패와 경쟁하는 Job 행을 기다리지 않고 잠근 뒤 만료 조건을 다시 검증한다.
        Optional<EmbeddingJob> candidate = embeddingJobRepository
            .findExpiredByIdForUpdateSkipLocked(jobId, recoveredAt);
        if (candidate.isEmpty()) {
            return RecoveryResult.skipped(jobId);
        }
        EmbeddingJob embeddingJob = candidate.get();
        validateExpiredOwnership(embeddingJob, jobId, recoveredAt);

        // 2. 현재 Claim Token으로 실제 시작된 Attempt만 조회하고 Job·Worker·Token 실행 Context를 검증한다.
        Optional<EmbeddingJobAttempt> attempt = embeddingJobAttemptRepository
            .findByEmbeddingJobIdAndClaimToken(embeddingJob.getId(), embeddingJob.getClaimToken());
        attempt.ifPresent(currentAttempt ->
            validateStartedAttempt(embeddingJob, currentAttempt, recoveredAt)
        );

        // 3. Token을 제외한 회수 원인 Event를 상태 전이 전 Snapshot으로 같은 Transaction에 기록한다.
        saveLeaseExpiredEvent(embeddingJob, attempt, recoveredAt);

        // 4. 기존 실패 정책으로 Attempt 종료, Retry 예약 또는 검색 상태의 최종 실패를 원자적으로 적용한다.
        failureTransitionService.transition(
            embeddingJob,
            attempt,
            LEASE_EXPIRED_FAILURE_CODE,
            LEASE_EXPIRED_MESSAGE,
            true,
            recoveredAt,
            Duration.ZERO
        );
        return RecoveryResult.recovered(embeddingJob);
    }

    private void validateRequest(Long jobId, LocalDateTime recoveredAt) {
        if (jobId == null || jobId <= 0 || recoveredAt == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }

    private void validateExpiredOwnership(
        EmbeddingJob embeddingJob,
        Long jobId,
        LocalDateTime recoveredAt
    ) {
        if (embeddingJob.getId() == null
            || !Objects.equals(embeddingJob.getId(), jobId)
            || embeddingJob.getStatus() != EmbeddingJobStatus.PROCESSING
            || embeddingJob.getLockedByWorker() == null
            || embeddingJob.getLockedByWorker().getId() == null
            || !isCanonicalUuid(embeddingJob.getClaimToken())
            || embeddingJob.getLockedAt() == null
            || embeddingJob.getLockExpiresAt() == null
            || !embeddingJob.getLockExpiresAt().isAfter(embeddingJob.getLockedAt())
            || embeddingJob.getLockExpiresAt().isAfter(recoveredAt)
            || embeddingJob.getDocumentVersion() == null
            || embeddingJob.getDocumentVersion().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }

    private boolean isCanonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void validateStartedAttempt(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        LocalDateTime recoveredAt
    ) {
        if (attempt.getId() == null
            || attempt.getEmbeddingJob() == null
            || !Objects.equals(attempt.getEmbeddingJob().getId(), embeddingJob.getId())
            || attempt.getWorkerNode() == null
            || !Objects.equals(
                attempt.getWorkerNode().getId(),
                embeddingJob.getLockedByWorker().getId()
            )
            || !Objects.equals(attempt.getClaimToken(), embeddingJob.getClaimToken())
            || attempt.getAttemptNo() <= 0
            || attempt.getStatus() != AttemptStatus.STARTED
            || attempt.getStartedAt() == null
            || attempt.getStartedAt().isAfter(recoveredAt)) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }

    private void saveLeaseExpiredEvent(
        EmbeddingJob embeddingJob,
        Optional<EmbeddingJobAttempt> attempt,
        LocalDateTime recoveredAt
    ) {
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.LEASE_EXPIRED)
            .fromStatus(EmbeddingJobStatus.PROCESSING.name())
            .toStatus(EmbeddingJobStatus.PROCESSING.name())
            .message(LEASE_EXPIRED_MESSAGE)
            .metadataJson(leaseExpiredMetadata(embeddingJob, attempt))
            .occurredAt(recoveredAt)
            .build());
    }

    private String leaseExpiredMetadata(
        EmbeddingJob embeddingJob,
        Optional<EmbeddingJobAttempt> attempt
    ) {
        return attempt
            .map(currentAttempt ->
                """
                    {"workerId":%d,"attemptId":%d,"attemptNo":%d,"expiredAt":"%s","retryCountBefore":%d}
                    """.formatted(
                        embeddingJob.getLockedByWorker().getId(),
                        currentAttempt.getId(),
                        currentAttempt.getAttemptNo(),
                        embeddingJob.getLockExpiresAt(),
                        embeddingJob.getRetryCount()
                    ).strip()
            )
            .orElseGet(() ->
                """
                    {"workerId":%d,"expiredAt":"%s","retryCountBefore":%d}
                    """.formatted(
                        embeddingJob.getLockedByWorker().getId(),
                        embeddingJob.getLockExpiresAt(),
                        embeddingJob.getRetryCount()
                    ).strip()
            );
    }

    /**
     * 복구 후보 한 건이 실제 상태 전이를 수행했는지와 전이 후 Job 상태를 전달하는 내부 결과.
     *
     * <p>Scheduler 집계에만 사용하며 Entity와 Claim Token을 외부로 노출하지 않는다.
     */
    public record RecoveryResult(
        Long jobId,
        boolean recovered,
        EmbeddingJobStatus status
    ) {

        private static RecoveryResult skipped(Long jobId) {
            return new RecoveryResult(jobId, false, null);
        }

        private static RecoveryResult recovered(EmbeddingJob embeddingJob) {
            return new RecoveryResult(embeddingJob.getId(), true, embeddingJob.getStatus());
        }
    }
}
