package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
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

/**
 * 문서 Chunk Embedding 생성의 준비와 완료 단계를 짧은 DB Transaction으로 분리해 수행한다.
 *
 * <p>두 단계는 Job을 먼저, Version을 다음 순서로 잠가 Claim 교체와 같은 Version의 동시 실행을
 * 직렬화한다. 준비 단계는 Job에 고정된 Model과 Chunk 불변 Snapshot만 외부 호출 구간에 전달한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentEmbeddingTransactionService {

    private static final String EMBEDDING_STARTED_MESSAGE = "Document Version Embedding 생성을 시작했습니다.";

    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final EmbeddingJobOwnershipValidator ownershipValidator;
    private final Clock clock;

    /**
     * 외부 Embedding 호출 전에 실행 소유권과 저장 상태를 검증하고 불변 작업 Snapshot을 만든다.
     */
    public PreparationResult prepare(
        Long jobId,
        Long attemptId,
        Long workerId,
        String claimToken
    ) {
        // 1. Claim 교체와 같은 Job의 중복 요청을 직렬화하도록 Job을 먼저 잠근다.
        EmbeddingJob embeddingJob = findLockedJob(jobId);
        LocalDateTime preparedAt = LocalDateTime.now(clock);
        ownershipValidator.validate(embeddingJob, workerId, claimToken, preparedAt);
        validateAttempt(embeddingJob, attemptId, workerId, claimToken);

        // 2. Job이 직접 가리키는 Version과 Model을 고정하고 Version 행을 잠근다.
        EmbeddingModel embeddingModel = findJobModel(embeddingJob);
        DocumentVersion documentVersion = findLockedJobVersion(embeddingJob);

        // 3. Chunk Set 전체를 순서대로 검증해 부분·중복·누락된 입력을 외부 호출 전에 차단한다.
        List<DocumentChunk> chunks = documentChunkRepository
            .findAllByDocumentVersionIdOrderByChunkIndexAsc(documentVersion.getId());
        validateChunks(documentVersion, chunks);

        // 4. Version 상태와 현재 Model의 저장 개수를 함께 비교해 작업·재개·완료 재생을 구분한다.
        EmbeddingState state = resolveState(documentVersion, embeddingModel, chunks.size());
        if (state == EmbeddingState.REPLAY) {
            return PreparationResult.replay(result(
                jobId,
                attemptId,
                documentVersion,
                embeddingModel,
                chunks.size(),
                false
            ));
        }

        // 5. 최초 CHUNKED 요청만 EMBEDDING 상태와 시작 이벤트를 같은 Transaction에 기록한다.
        if (documentVersion.getStatus() == DocumentVersionStatus.CHUNKED) {
            documentVersion.markEmbedding();
            indexingEventRepository.save(IndexingEvent.builder()
                .embeddingJob(embeddingJob)
                .eventType(IndexingEventType.EMBEDDING_STARTED)
                .fromStatus(DocumentVersionStatus.CHUNKED.name())
                .toStatus(DocumentVersionStatus.EMBEDDING.name())
                .message(EMBEDDING_STARTED_MESSAGE)
                .occurredAt(preparedAt)
                .build());
        }

        return PreparationResult.work(new EmbeddingWork(
            documentVersion.getId(),
            embeddingModel.getId(),
            embeddingModel.getDimension(),
            chunks.stream()
                .map(chunk -> new ChunkSnapshot(
                    chunk.getId(),
                    chunk.getChunkIndex(),
                    chunk.getChunkText(),
                    chunk.getContentHash()
                ))
                .toList()
        ));
    }

    private EmbeddingJob findLockedJob(Long jobId) {
        return embeddingJobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));
    }

    private DocumentVersion findLockedJobVersion(EmbeddingJob embeddingJob) {
        if (embeddingJob.getDocumentVersion() == null
            || embeddingJob.getDocumentVersion().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
        return documentVersionRepository.findByIdForUpdate(embeddingJob.getDocumentVersion().getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT));
    }

    private EmbeddingModel findJobModel(EmbeddingJob embeddingJob) {
        EmbeddingModel embeddingModel = embeddingJob.getEmbeddingModel();
        if (embeddingModel == null
            || embeddingModel.getId() == null
            || embeddingModel.getDimension() <= 0) {
            throw new DocGridException(ErrorCode.EMBEDDING_MODEL_NOT_CONFIGURED);
        }
        return embeddingModel;
    }

    private void validateAttempt(
        EmbeddingJob embeddingJob,
        Long attemptId,
        Long workerId,
        String claimToken
    ) {
        EmbeddingJobAttempt attempt = embeddingJobAttemptRepository
            .findByEmbeddingJobIdAndClaimToken(embeddingJob.getId(), claimToken)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID));

        if (!Objects.equals(attempt.getId(), attemptId)
            || attempt.getStatus() != AttemptStatus.STARTED
            || attempt.getWorkerNode() == null
            || !Objects.equals(attempt.getWorkerNode().getId(), workerId)) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID);
        }
    }

    private void validateChunks(
        DocumentVersion documentVersion,
        List<DocumentChunk> chunks
    ) {
        if (chunks == null || chunks.isEmpty()) {
            throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
        }

        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            if (chunk == null
                || chunk.getId() == null
                || chunk.getDocumentVersion() == null
                || !Objects.equals(chunk.getDocumentVersion().getId(), documentVersion.getId())
                || chunk.getChunkIndex() != index
                || !StringUtils.hasText(chunk.getChunkText())
                || !StringUtils.hasText(chunk.getContentHash())
                || !chunk.getContentHash().matches("[0-9a-f]{64}")) {
                throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
            }
        }
    }

    private EmbeddingState resolveState(
        DocumentVersion documentVersion,
        EmbeddingModel embeddingModel,
        int chunkCount
    ) {
        long embeddingCount = embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(
            documentVersion.getId(),
            embeddingModel.getId()
        );

        if (documentVersion.getStatus() == DocumentVersionStatus.CHUNKED) {
            if (embeddingCount != 0) {
                throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
            }
            return EmbeddingState.WORK;
        }
        if (documentVersion.getStatus() == DocumentVersionStatus.EMBEDDING) {
            if (embeddingCount == 0) {
                return EmbeddingState.WORK;
            }
            if (embeddingCount == chunkCount) {
                return EmbeddingState.REPLAY;
            }
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
        if (embeddingCount != 0) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
        throw new DocGridException(ErrorCode.DOCUMENT_VERSION_EMBEDDING_NOT_ALLOWED);
    }

    private CompletionResult result(
        Long jobId,
        Long attemptId,
        DocumentVersion documentVersion,
        EmbeddingModel embeddingModel,
        int chunkCount,
        boolean created
    ) {
        return new CompletionResult(
            jobId,
            attemptId,
            documentVersion.getId(),
            embeddingModel.getId(),
            chunkCount,
            chunkCount,
            documentVersion.getStatus(),
            created
        );
    }

    private enum EmbeddingState {
        WORK,
        REPLAY
    }

    /**
     * 외부 호출에 필요한 Version·Model 식별자와 정렬된 Chunk 값의 불변 Snapshot.
     */
    public record EmbeddingWork(
        Long documentVersionId,
        Long embeddingModelId,
        int dimension,
        List<ChunkSnapshot> chunks
    ) {

        public EmbeddingWork {
            chunks = List.copyOf(chunks);
        }
    }

    /**
     * 외부 호출에 전달하는 단일 Chunk의 식별자, 순서, Text와 내용 Hash Snapshot.
     */
    public record ChunkSnapshot(
        Long chunkId,
        int chunkIndex,
        String chunkText,
        String contentHash
    ) {
    }

    /**
     * 준비 Transaction이 전달하는 외부 작업 Snapshot 또는 기존 완료 결과 중 하나를 표현한다.
     */
    public record PreparationResult(
        EmbeddingWork work,
        CompletionResult replayResult
    ) {

        static PreparationResult work(EmbeddingWork work) {
            return new PreparationResult(work, null);
        }

        static PreparationResult replay(CompletionResult replayResult) {
            return new PreparationResult(null, replayResult);
        }

        public boolean isReplay() {
            return replayResult != null;
        }
    }

    /**
     * 저장 생성 여부와 API 응답에 필요한 문서 Embedding Set 요약을 전달한다.
     */
    public record CompletionResult(
        Long jobId,
        Long attemptId,
        Long documentVersionId,
        Long embeddingModelId,
        int chunkCount,
        int embeddingCount,
        DocumentVersionStatus documentVersionStatus,
        boolean created
    ) {
    }
}
