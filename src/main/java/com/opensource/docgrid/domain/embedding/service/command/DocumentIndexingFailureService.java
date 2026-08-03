package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobAttemptConverter;
import com.opensource.docgrid.domain.embedding.dto.request.FailDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingFailureResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 유효한 Worker Claim의 인덱싱 실패를 Attempt 이력과 Retry 또는 최종 실패 상태로 원자 기록한다.
 *
 * <p>Job → Version → Document 순서의 행 잠금을 유지해 완료 요청 및 새 Version 업로드와 직렬화한다.
 * 이 Service는 외부 I/O를 수행하지 않으며, 실패 분류 정책과 남은 횟수만으로 Retry 여부를 결정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentIndexingFailureService {

    private static final String PARSE_FAILURE_MESSAGE = "Document Version 파싱 단계가 실패했습니다.";
    private static final String EMBEDDING_FAILURE_MESSAGE = "Document Version 임베딩 단계가 실패했습니다.";
    private static final String RETRY_MESSAGE = "Embedding Job 재시도를 예약했습니다.";
    private static final String TERMINAL_FAILURE_MESSAGE = "Embedding Job을 최종 실패로 종료했습니다.";

    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentRepository documentRepository;
    private final EmbeddingRepository embeddingRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final EmbeddingJobOwnershipValidator ownershipValidator;
    private final EmbeddingJobAttemptConverter attemptConverter;
    private final IndexingWorkerProperties workerProperties;
    private final Clock clock;

    /**
     * 현재 Attempt를 실패로 종결하고 서버 정책에 따라 Job을 재예약하거나 최종 종료한다.
     */
    public DocumentIndexingFailureResponse fail(
        Long jobId,
        Long attemptId,
        FailDocumentIndexingRequest request
    ) {
        // 1. Claim 세대 교체와 완료·실패 경쟁을 Job 행에서 직렬화하고 요청 Attempt를 먼저 확인한다.
        EmbeddingJob embeddingJob = findLockedJob(jobId);
        EmbeddingJobAttempt attempt = findAttempt(embeddingJob, request.claimToken());

        // 2. 이미 실패한 같은 실행은 현재 Job과 Lease가 바뀌었어도 저장된 최초 결과만 재생한다.
        if (attempt.getStatus() == AttemptStatus.FAILED) {
            validateFailureReplay(embeddingJob, attempt, attemptId, request);
            return attemptConverter.toFailureResponse(attempt);
        }
        if (attempt.getStatus() != AttemptStatus.STARTED) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_FAILURE_CONFLICT);
        }
        if (embeddingJob.getStatus() != EmbeddingJobStatus.PROCESSING) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_PROCESSING);
        }

        // PostgreSQL TIMESTAMP 정밀도와 맞춰 최초 실패와 DB 재생 응답의 시각을 동일하게 유지한다.
        LocalDateTime failedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);

        // 3. 현재 소유권과 STARTED Attempt를 검증한 뒤 Version과 Document를 정해진 순서로 잠근다.
        ownershipValidator.validate(
            embeddingJob,
            request.workerId(),
            request.claimToken(),
            failedAt
        );
        validateStartedAttempt(embeddingJob, attempt, attemptId, request, failedAt);
        DocumentVersion documentVersion = findLockedVersion(embeddingJob);
        Document document = findLockedDocument(documentVersion);
        validateFailureTarget(embeddingJob, documentVersion, document);

        // 4. 상태 변경 전에 단계 이벤트와 소요 시간을 고정해 부분 전이가 남지 않게 한다.
        DocumentVersionStatus failedFromStatus = documentVersion.getStatus();
        IndexingEventType stageEventType = resolveStageEventType(failedFromStatus);
        long durationMs = Duration.between(attempt.getStartedAt(), failedAt).toMillis();
        attempt.markFailed(
            failedAt,
            durationMs,
            request.failureType().name(),
            request.errorMessage()
        );

        // 5. Retry 가능 정책과 잔여 횟수에 따라 Queue 복귀 또는 모든 대상의 최종 실패를 함께 기록한다.
        if (request.failureType().isRetryable() && embeddingJob.hasRemainingRetries()) {
            scheduleRetry(
                embeddingJob,
                attempt,
                failedFromStatus,
                stageEventType,
                request.failureType(),
                failedAt
            );
        } else {
            terminateFailure(
                embeddingJob,
                attempt,
                documentVersion,
                document,
                failedFromStatus,
                stageEventType,
                request.failureType(),
                failedAt
            );
        }

        log.info(
            "문서 인덱싱 실패 기록: jobId={}, attemptId={}, failureType={}, retryCount={}, terminal={}",
            embeddingJob.getId(),
            attempt.getId(),
            request.failureType(),
            embeddingJob.getRetryCount(),
            embeddingJob.getStatus() == EmbeddingJobStatus.FAILED
        );
        return attemptConverter.toFailureResponse(attempt);
    }

    private EmbeddingJob findLockedJob(Long jobId) {
        return embeddingJobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));
    }

    private EmbeddingJobAttempt findAttempt(EmbeddingJob embeddingJob, String claimToken) {
        return embeddingJobAttemptRepository
            .findByEmbeddingJobIdAndClaimToken(embeddingJob.getId(), claimToken)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID));
    }

    private void validateFailureReplay(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        Long attemptId,
        FailDocumentIndexingRequest request
    ) {
        boolean identityMatches = Objects.equals(attempt.getId(), attemptId)
            && attempt.getEmbeddingJob() != null
            && Objects.equals(attempt.getEmbeddingJob().getId(), embeddingJob.getId())
            && attempt.getWorkerNode() != null
            && Objects.equals(attempt.getWorkerNode().getId(), request.workerId())
            && Objects.equals(attempt.getClaimToken(), request.claimToken());
        if (!identityMatches) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID);
        }

        boolean failureMatches = Objects.equals(attempt.getErrorCode(), request.failureType().name())
            && Objects.equals(attempt.getErrorMessage(), request.errorMessage());
        if (!failureMatches) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_FAILURE_CONFLICT);
        }
        if (attempt.getEndedAt() == null
            || attempt.getDurationMs() == null
            || attempt.getDurationMs() < 0
            || attempt.getStartedAt() == null
            || attempt.getStartedAt().isAfter(attempt.getEndedAt())
            || Duration.between(attempt.getStartedAt(), attempt.getEndedAt()).toMillis()
                != attempt.getDurationMs()) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }

    private void validateStartedAttempt(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        Long attemptId,
        FailDocumentIndexingRequest request,
        LocalDateTime failedAt
    ) {
        if (!Objects.equals(attempt.getId(), attemptId)
            || attempt.getEmbeddingJob() == null
            || !Objects.equals(attempt.getEmbeddingJob().getId(), embeddingJob.getId())
            || attempt.getWorkerNode() == null
            || !Objects.equals(attempt.getWorkerNode().getId(), request.workerId())
            || !Objects.equals(attempt.getClaimToken(), request.claimToken())
            || attempt.getStartedAt() == null) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID);
        }
        if (attempt.getStartedAt().isAfter(failedAt)) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }

    private DocumentVersion findLockedVersion(EmbeddingJob embeddingJob) {
        if (embeddingJob.getDocumentVersion() == null
            || embeddingJob.getDocumentVersion().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
        return documentVersionRepository.findByIdForUpdate(embeddingJob.getDocumentVersion().getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT));
    }

    private Document findLockedDocument(DocumentVersion documentVersion) {
        if (documentVersion.getDocument() == null
            || documentVersion.getDocument().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
        return documentRepository.findByIdForUpdate(documentVersion.getDocument().getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT));
    }

    private void validateFailureTarget(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        Document document
    ) {
        if (!Objects.equals(embeddingJob.getDocumentVersion().getId(), documentVersion.getId())
            || documentVersion.getDocument() == null
            || !Objects.equals(documentVersion.getDocument().getId(), document.getId())
            || document.getDeletedAt() != null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
        resolveStageEventType(documentVersion.getStatus());
    }

    private IndexingEventType resolveStageEventType(DocumentVersionStatus versionStatus) {
        return switch (versionStatus) {
            case UPLOADED, PARSING -> IndexingEventType.PARSE_FAILED;
            case CHUNKED, EMBEDDING -> IndexingEventType.EMBEDDING_FAILED;
            default -> throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        };
    }

    private void scheduleRetry(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        DocumentVersionStatus versionStatus,
        IndexingEventType stageEventType,
        IndexingFailureType failureType,
        LocalDateTime failedAt
    ) {
        Duration retryDelay = calculateRetryDelay(embeddingJob.getRetryCount());
        LocalDateTime nextRetryAt = failedAt.plus(retryDelay);
        embeddingJob.scheduleRetry(failureType.name(), attempt.getErrorMessage(), nextRetryAt);

        // Version 상태는 재개 지점으로 보존하고 같은 시각에 단계 실패와 Queue 재예약 이벤트를 남긴다.
        saveStageFailureEvent(
            embeddingJob,
            attempt,
            stageEventType,
            versionStatus.name(),
            versionStatus.name(),
            failureType,
            failedAt
        );
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.RETRY)
            .fromStatus(EmbeddingJobStatus.PROCESSING.name())
            .toStatus(EmbeddingJobStatus.PENDING.name())
            .message(RETRY_MESSAGE)
            .metadataJson(retryMetadata(embeddingJob, nextRetryAt))
            .occurredAt(failedAt)
            .build());
    }

    private void terminateFailure(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        DocumentVersion documentVersion,
        Document document,
        DocumentVersionStatus versionStatus,
        IndexingEventType stageEventType,
        IndexingFailureType failureType,
        LocalDateTime failedAt
    ) {
        // 1. 대상 Version의 검색 가능 Set을 비활성화하고 처리 중 상태를 최종 실패로 종결한다.
        embeddingRepository.markActiveAsStaleByDocumentVersionId(documentVersion.getId());
        documentVersion.markFailed();
        transitionDocumentOnTerminalFailure(document, documentVersion);
        embeddingJob.markFailed(failureType.name(), attempt.getErrorMessage(), failedAt);

        // 2. 단계 실패와 Job 최종 실패 이벤트를 같은 Transaction과 시각에 append한다.
        saveStageFailureEvent(
            embeddingJob,
            attempt,
            stageEventType,
            versionStatus.name(),
            DocumentVersionStatus.FAILED.name(),
            failureType,
            failedAt
        );
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.FAILED)
            .fromStatus(EmbeddingJobStatus.PROCESSING.name())
            .toStatus(EmbeddingJobStatus.FAILED.name())
            .message(TERMINAL_FAILURE_MESSAGE)
            .metadataJson(terminalFailureMetadata(embeddingJob))
            .occurredAt(failedAt)
            .build());
    }

    private void transitionDocumentOnTerminalFailure(
        Document document,
        DocumentVersion failedVersion
    ) {
        DocumentVersion currentVersion = document.getCurrentVersion();
        if (currentVersion == null
            || currentVersion.getId() == null
            || currentVersion.getDocument() == null
            || !Objects.equals(currentVersion.getDocument().getId(), document.getId())) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }

        // 이전 INDEXED Version이 있으면 검색 가용성과 현재 포인터를 그대로 보존한다.
        if (!Objects.equals(currentVersion.getId(), failedVersion.getId())
            && currentVersion.getStatus() == DocumentVersionStatus.INDEXED
            && document.getStatus() == DocumentStatus.INDEXED) {
            return;
        }

        boolean noSearchableVersion = Objects.equals(currentVersion.getId(), failedVersion.getId())
            || currentVersion.getStatus() == DocumentVersionStatus.FAILED;
        if (noSearchableVersion
            && (document.getStatus() == DocumentStatus.UPLOADED
                || document.getStatus() == DocumentStatus.INDEXING
                || document.getStatus() == DocumentStatus.FAILED)) {
            document.markFailed();
            return;
        }
        throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
    }

    private void saveStageFailureEvent(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        IndexingEventType eventType,
        String fromStatus,
        String toStatus,
        IndexingFailureType failureType,
        LocalDateTime failedAt
    ) {
        String message = eventType == IndexingEventType.PARSE_FAILED
            ? PARSE_FAILURE_MESSAGE
            : EMBEDDING_FAILURE_MESSAGE;
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(eventType)
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .message(message)
            .metadataJson(stageFailureMetadata(attempt, failureType))
            .occurredAt(failedAt)
            .build());
    }

    private Duration calculateRetryDelay(int currentRetryCount) {
        Duration delay = workerProperties.getRetryInitialDelay();
        Duration maxDelay = workerProperties.getRetryMaxDelay();
        for (int retry = 0; retry < currentRetryCount; retry++) {
            // 두 배가 최대 지연에 닿는 순간 상한을 반환해 Duration 곱셈 Overflow를 피한다.
            if (delay.compareTo(maxDelay.minus(delay)) >= 0) {
                return maxDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay;
    }

    private String stageFailureMetadata(
        EmbeddingJobAttempt attempt,
        IndexingFailureType failureType
    ) {
        return "{\"attemptId\":" + attempt.getId()
            + ",\"attemptNo\":" + attempt.getAttemptNo()
            + ",\"failureType\":\"" + failureType.name() + "\"}";
    }

    private String retryMetadata(EmbeddingJob embeddingJob, LocalDateTime nextRetryAt) {
        return "{\"retryCount\":" + embeddingJob.getRetryCount()
            + ",\"nextRetryAt\":\"" + nextRetryAt + "\"}";
    }

    private String terminalFailureMetadata(EmbeddingJob embeddingJob) {
        return "{\"retryCount\":" + embeddingJob.getRetryCount()
            + ",\"maxRetryCount\":" + embeddingJob.getMaxRetryCount() + "}";
    }
}
