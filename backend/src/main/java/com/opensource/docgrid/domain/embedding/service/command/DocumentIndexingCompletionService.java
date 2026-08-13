package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.dto.request.CompleteDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingCompletionResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.embedding.event.EmbeddingJobStatusChangedEvent;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
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
 * 완성된 Embedding Set을 검증하고 문서 Version을 검색 가능한 현재 Version으로 확정한다.
 *
 * <p>외부 I/O 없이 하나의 짧은 Transaction에서 Job → Version → Document 순서로 잠근다.
 * 모든 개수·관계·상태 불변식이 확인된 뒤 이전 Embedding 비활성화, 상태 전이와 INDEXED 이벤트를
 * 함께 커밋하므로 검색 요청은 완료 전이나 완료 후의 일관된 상태만 관찰한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentIndexingCompletionService {

    private static final String INDEXED_MESSAGE = "Document Version 인덱싱을 완료했습니다.";
    private static final Set<EmbeddingJobStatus> ACTIVE_JOB_STATUSES = EnumSet.of(
        EmbeddingJobStatus.PENDING,
        EmbeddingJobStatus.PROCESSING
    );
    private static final Set<DocumentStatus> COMPLETABLE_DOCUMENT_STATUSES = EnumSet.of(
        DocumentStatus.UPLOADED,
        DocumentStatus.INDEXING,
        DocumentStatus.INDEXED
    );

    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final EmbeddingJobOwnershipValidator ownershipValidator;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 현재 Claim 실행이 생성한 전체 Embedding Set을 문서의 검색 가능 상태로 확정한다.
     */
    public DocumentIndexingCompletionResponse complete(
        Long jobId,
        Long attemptId,
        CompleteDocumentIndexingRequest request
    ) {
        // 1. Claim 교체와 같은 Job의 중복 완료를 직렬화하고 완료 기준 시각을 고정한다.
        EmbeddingJob embeddingJob = findLockedJob(jobId);
        // PostgreSQL TIMESTAMP 정밀도와 맞춰 최초 응답과 DB에서 읽은 재생 응답의 시각을 동일하게 유지한다.
        LocalDateTime completedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);

        // 2. 이미 완료된 같은 실행은 저장된 최초 결과를 재생하고 Lease와 가변 검색 상태는 다시 검증하지 않는다.
        if (embeddingJob.getStatus() == EmbeddingJobStatus.INDEXED) {
            return replayIndexedJob(embeddingJob, attemptId, request);
        }
        if (embeddingJob.getStatus() != EmbeddingJobStatus.PROCESSING) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_NOT_ALLOWED);
        }

        // 3. 소유권과 Attempt 실행 Context를 검증한 뒤 Version과 Document를 정해진 순서로 잠근다.
        ownershipValidator.validate(
            embeddingJob,
            request.workerId(),
            request.claimToken(),
            completedAt
        );
        EmbeddingJobAttempt attempt = resolveStartedAttempt(
            embeddingJob,
            attemptId,
            request.workerId(),
            request.claimToken(),
            completedAt
        );
        DocumentVersion documentVersion = findLockedVersion(embeddingJob);
        Document document = findLockedDocument(documentVersion);
        EmbeddingModel embeddingModel = validateModel(embeddingJob);

        // 4. 최신 대상과 전체 Embedding Set 및 완료 이벤트 사전 상태를 변경 전에 모두 검증한다.
        validateCompletionTarget(embeddingJob, documentVersion, document);
        CompletionCounts counts = validateEmbeddingSet(
            embeddingJob,
            documentVersion,
            document,
            embeddingModel
        );
        validateNoIndexedEvent(embeddingJob);

        // 5. 이전 검색 Set을 비활성화한 뒤 모든 완료 상태와 이벤트를 같은 시각으로 기록한다.
        stalePreviousEmbeddings(document, documentVersion);
        long durationMs = Duration.between(attempt.getStartedAt(), completedAt).toMillis();
        transitionAndRecordEvent(
            embeddingJob,
            attempt,
            documentVersion,
            document,
            completedAt,
            durationMs
        );

        // 6. 대시보드가 최신 집계를 다시 계산하도록 상태 전이를 알린다. AFTER_COMMIT 구독자만
        //    반응하므로 이 Transaction이 실제로 커밋된 뒤에만 push로 이어진다.
        applicationEventPublisher.publishEvent(new EmbeddingJobStatusChangedEvent(embeddingJob.getId()));

        log.info(
            "문서 인덱싱 완료: jobId={}, attemptId={}, documentId={}, versionId={}, "
                + "chunkCount={}, embeddingCount={}, durationMs={}",
            embeddingJob.getId(),
            attempt.getId(),
            document.getId(),
            documentVersion.getId(),
            counts.chunkCount(),
            counts.embeddingCount(),
            durationMs
        );
        return response(
            embeddingJob,
            attempt,
            documentVersion,
            document,
            embeddingModel,
            completedAt,
            durationMs
        );
    }

    private DocumentIndexingCompletionResponse replayIndexedJob(
        EmbeddingJob embeddingJob,
        Long attemptId,
        CompleteDocumentIndexingRequest request
    ) {
        // 1. 완료 시 보존한 Worker와 Token이 같은 실행의 재요청인지 확인한다.
        validateCompletedIdentity(embeddingJob, request.workerId(), request.claimToken());
        EmbeddingJobAttempt attempt = resolveCompletedAttempt(
            embeddingJob,
            attemptId,
            request.workerId(),
            request.claimToken()
        );

        // 2. 최초 완료와 같은 Job → Version → Document 잠금 순서를 유지하되 최신 Version 여부는 요구하지 않는다.
        DocumentVersion documentVersion = findLockedVersion(embeddingJob);
        Document document = findLockedDocument(documentVersion);
        EmbeddingModel embeddingModel = findCompletedModel(embeddingJob);

        // 3. 최초 완료의 저장 시각·상태·단일 이벤트가 온전한지 검증하고 어떠한 값도 다시 계산하지 않는다.
        validateCompletedState(
            embeddingJob,
            attempt,
            documentVersion,
            document
        );
        log.info(
            "문서 인덱싱 완료 재생: jobId={}, attemptId={}, completedAt={}, replay=true",
            embeddingJob.getId(),
            attempt.getId(),
            embeddingJob.getCompletedAt()
        );
        return response(
            embeddingJob,
            attempt,
            documentVersion,
            document,
            embeddingModel,
            embeddingJob.getCompletedAt(),
            attempt.getDurationMs()
        );
    }

    private EmbeddingJob findLockedJob(Long jobId) {
        return embeddingJobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));
    }

    private EmbeddingJobAttempt resolveStartedAttempt(
        EmbeddingJob embeddingJob,
        Long attemptId,
        Long workerId,
        String claimToken,
        LocalDateTime completedAt
    ) {
        EmbeddingJobAttempt attempt = embeddingJobAttemptRepository
            .findByEmbeddingJobIdAndClaimToken(embeddingJob.getId(), claimToken)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID));

        if (!Objects.equals(attempt.getId(), attemptId)
            || attempt.getEmbeddingJob() == null
            || !Objects.equals(attempt.getEmbeddingJob().getId(), embeddingJob.getId())
            || attempt.getWorkerNode() == null
            || !Objects.equals(attempt.getWorkerNode().getId(), workerId)
            || attempt.getStatus() != AttemptStatus.STARTED
            || attempt.getStartedAt() == null) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID);
        }
        if (attempt.getStartedAt().isAfter(completedAt)) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT);
        }
        return attempt;
    }

    private DocumentVersion findLockedVersion(EmbeddingJob embeddingJob) {
        if (embeddingJob.getDocumentVersion() == null
            || embeddingJob.getDocumentVersion().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT);
        }
        return documentVersionRepository.findByIdForUpdate(embeddingJob.getDocumentVersion().getId())
            .orElseThrow(() ->
                new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT));
    }

    private Document findLockedDocument(DocumentVersion documentVersion) {
        if (documentVersion.getDocument() == null
            || documentVersion.getDocument().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT);
        }
        return documentRepository.findByIdForUpdate(documentVersion.getDocument().getId())
            .orElseThrow(() ->
                new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT));
    }

    private EmbeddingModel validateModel(EmbeddingJob embeddingJob) {
        EmbeddingModel embeddingModel = embeddingJob.getEmbeddingModel();
        if (embeddingModel == null
            || embeddingModel.getId() == null
            || embeddingModel.getDimension() <= 0
            || !embeddingModel.isActive()
            || !embeddingModel.isSearchable()) {
            throw new DocGridException(ErrorCode.EMBEDDING_MODEL_NOT_CONFIGURED);
        }
        return embeddingModel;
    }

    private EmbeddingModel findCompletedModel(EmbeddingJob embeddingJob) {
        EmbeddingModel embeddingModel = embeddingJob.getEmbeddingModel();
        if (embeddingModel == null || embeddingModel.getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT);
        }
        return embeddingModel;
    }

    private void validateCompletedIdentity(
        EmbeddingJob embeddingJob,
        Long workerId,
        String claimToken
    ) {
        if (embeddingJob.getLockedByWorker() == null
            || embeddingJob.getLockedByWorker().getId() == null
            || !StringUtils.hasText(embeddingJob.getClaimToken())) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT);
        }
        if (!Objects.equals(embeddingJob.getLockedByWorker().getId(), workerId)
            || !Objects.equals(embeddingJob.getClaimToken(), claimToken)) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID);
        }
    }

    private EmbeddingJobAttempt resolveCompletedAttempt(
        EmbeddingJob embeddingJob,
        Long attemptId,
        Long workerId,
        String claimToken
    ) {
        EmbeddingJobAttempt attempt = embeddingJobAttemptRepository
            .findByEmbeddingJobIdAndClaimToken(embeddingJob.getId(), claimToken)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID));
        if (!Objects.equals(attempt.getId(), attemptId)
            || attempt.getEmbeddingJob() == null
            || !Objects.equals(attempt.getEmbeddingJob().getId(), embeddingJob.getId())
            || attempt.getWorkerNode() == null
            || !Objects.equals(attempt.getWorkerNode().getId(), workerId)
            || !Objects.equals(attempt.getClaimToken(), claimToken)
            || attempt.getStatus() != AttemptStatus.SUCCESS) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID);
        }
        return attempt;
    }

    private void validateCompletedState(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        DocumentVersion documentVersion,
        Document document
    ) {
        if (embeddingJob.getCompletedAt() == null
            || attempt.getStartedAt() == null
            || attempt.getEndedAt() == null
            || attempt.getDurationMs() == null
            || attempt.getDurationMs() < 0
            || documentVersion.getStatus() != DocumentVersionStatus.INDEXED
            || documentVersion.getIndexedAt() == null
            || documentVersion.getDocument() == null
            || !Objects.equals(documentVersion.getDocument().getId(), document.getId())
            || !Objects.equals(embeddingJob.getCompletedAt(), attempt.getEndedAt())
            || !Objects.equals(embeddingJob.getCompletedAt(), documentVersion.getIndexedAt())
            || Duration.between(attempt.getStartedAt(), attempt.getEndedAt()).toMillis()
                != attempt.getDurationMs()
            || indexingEventRepository.countByEmbeddingJobIdAndEventType(
                embeddingJob.getId(),
                IndexingEventType.INDEXED
            ) != 1) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT);
        }
    }

    private void validateCompletionTarget(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        Document document
    ) {
        if (documentVersion.getStatus() != DocumentVersionStatus.EMBEDDING) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_NOT_ALLOWED);
        }
        if (!Objects.equals(embeddingJob.getDocumentVersion().getId(), documentVersion.getId())
            || !Objects.equals(documentVersion.getDocument().getId(), document.getId())) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT);
        }
        if (!COMPLETABLE_DOCUMENT_STATUSES.contains(document.getStatus())
            || document.getDeletedAt() != null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_NOT_ALLOWED);
        }

        DocumentVersion currentVersion = document.getCurrentVersion();
        if (currentVersion == null
            || currentVersion.getId() == null
            || currentVersion.getDocument() == null
            || !Objects.equals(currentVersion.getDocument().getId(), document.getId())) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT);
        }
        if (currentVersion.getVersionNo() > documentVersion.getVersionNo()) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_STALE_COMPLETION);
        }

        DocumentVersion latestVersion = documentVersionRepository
            .findTopByDocumentIdOrderByVersionNoDesc(document.getId())
            .orElseThrow(() ->
                new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT));
        if (!Objects.equals(latestVersion.getId(), documentVersion.getId())) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_STALE_COMPLETION);
        }
    }

    private CompletionCounts validateEmbeddingSet(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        Document document,
        EmbeddingModel embeddingModel
    ) {
        long activeJobCount = embeddingJobRepository.countByDocumentVersionIdAndStatusIn(
            documentVersion.getId(),
            ACTIVE_JOB_STATUSES
        );
        long chunkCount = documentChunkRepository.countByDocumentVersionId(documentVersion.getId());
        long allEmbeddingCount = embeddingRepository.countByDocumentVersionId(documentVersion.getId());
        long modelEmbeddingCount =
            embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(
                documentVersion.getId(),
                embeddingModel.getId()
            );
        long activeEmbeddingCount =
            embeddingRepository.countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
                documentVersion.getId(),
                embeddingModel.getId(),
                EmbeddingStatus.ACTIVE
            );
        long invalidEmbeddingCount = embeddingRepository.countInvalidCompletionRows(
            document.getId(),
            documentVersion.getId(),
            embeddingModel.getId(),
            embeddingModel.getDimension()
        );

        if (activeJobCount != 1
            || chunkCount <= 0
            || modelEmbeddingCount != chunkCount
            || activeEmbeddingCount != chunkCount
            || invalidEmbeddingCount != 0) {
            log.error(
                "문서 인덱싱 완료 데이터 불일치: jobId={}, documentId={}, versionId={}, "
                    + "activeJobCount={}, chunkCount={}, allEmbeddingCount={}, modelEmbeddingCount={}, "
                    + "activeEmbeddingCount={}, invalidEmbeddingCount={}",
                embeddingJob.getId(),
                document.getId(),
                documentVersion.getId(),
                activeJobCount,
                chunkCount,
                allEmbeddingCount,
                modelEmbeddingCount,
                activeEmbeddingCount,
                invalidEmbeddingCount
            );
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT);
        }
        return new CompletionCounts(chunkCount, modelEmbeddingCount);
    }

    private void validateNoIndexedEvent(EmbeddingJob embeddingJob) {
        if (indexingEventRepository.countByEmbeddingJobIdAndEventType(
            embeddingJob.getId(),
            IndexingEventType.INDEXED
        ) != 0) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT);
        }
    }

    private void stalePreviousEmbeddings(
        Document document,
        DocumentVersion documentVersion
    ) {
        DocumentVersion previousCurrentVersion = document.getCurrentVersion();
        if (!Objects.equals(previousCurrentVersion.getId(), documentVersion.getId())) {
            embeddingRepository.markActiveAsStaleByDocumentVersionId(
                previousCurrentVersion.getId()
            );
        }
    }

    private void transitionAndRecordEvent(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        DocumentVersion documentVersion,
        Document document,
        LocalDateTime completedAt,
        long durationMs
    ) {
        // 1. 새 검색 대상을 완성한 뒤 Document 포인터가 INDEXED Version만 가리키게 한다.
        documentVersion.markIndexed(completedAt);
        document.activateIndexedVersion(documentVersion);

        // 2. 실행 이력과 Queue 상태를 같은 완료 시각으로 종결한다.
        attempt.markSuccess(completedAt, durationMs);
        embeddingJob.markIndexed(completedAt);

        // 3. 완료 전이와 동일 Transaction에서 단일 INDEXED 이벤트를 append한다.
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.INDEXED)
            .fromStatus(DocumentVersionStatus.EMBEDDING.name())
            .toStatus(DocumentVersionStatus.INDEXED.name())
            .message(INDEXED_MESSAGE)
            .occurredAt(completedAt)
            .build());
    }

    private DocumentIndexingCompletionResponse response(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        DocumentVersion documentVersion,
        Document document,
        EmbeddingModel embeddingModel,
        LocalDateTime completedAt,
        long durationMs
    ) {
        return new DocumentIndexingCompletionResponse(
            embeddingJob.getId(),
            attempt.getId(),
            document.getId(),
            documentVersion.getId(),
            embeddingModel.getId(),
            embeddingJob.getStatus(),
            attempt.getStatus(),
            documentVersion.getStatus(),
            completedAt,
            durationMs
        );
    }

    /**
     * 완료 전 검증된 Chunk와 Embedding 집계 결과를 로그와 후속 응답 처리에 전달한다.
     */
    private record CompletionCounts(long chunkCount, long embeddingCount) {
    }
}
