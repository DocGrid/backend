package com.opensource.docgrid.domain.document.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.service.DocumentChunkDraft;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentChunksResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobOwnershipValidator;
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
 * Document Chunk 생성의 준비와 완료 단계를 각각 짧은 DB Transaction으로 수행한다.
 *
 * <p>두 단계 모두 Job을 먼저, Version을 다음 순서로 잠그고 현재 소유권·Attempt를 검증한다.
 * 준비 단계는 형식별 외부 파싱용 Snapshot을 만들며, 완료 단계는 Page·Section Metadata를 포함한
 * Chunk Set·CHUNKED 상태·이벤트를 원자적으로 저장한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentChunkTransactionService {

    private static final Set<String> TXT_CONTENT_TYPES = Set.of("text/plain");
    private static final Set<String> MARKDOWN_CONTENT_TYPES = Set.of("text/plain", "text/markdown");
    private static final Set<String> PDF_CONTENT_TYPES = Set.of("application/pdf");
    private static final Set<String> DOCX_CONTENT_TYPES = Set.of(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );
    private static final String PARSE_STARTED_MESSAGE = "Document Version 텍스트 파싱을 시작했습니다.";
    private static final String CHUNKED_MESSAGE = "Document Version Chunk 저장을 완료했습니다.";

    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final EmbeddingJobOwnershipValidator ownershipValidator;
    private final Clock clock;

    /**
     * 외부 파일 작업 전에 실행 Context와 Version을 검증하고 불변 Snapshot을 만든다.
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

        // 2. Job이 직접 가리키는 Version을 잠가 currentVersion과 무관한 처리 대상을 고정한다.
        DocumentVersion documentVersion = findLockedJobVersion(embeddingJob);
        validateSupportedFile(documentVersion);

        // 3. 상태와 기존 Chunk를 함께 검사해 유효한 완료 재생과 내부 데이터 모순을 구분한다.
        ChunkState chunkState = resolveChunkState(documentVersion);
        if (chunkState == ChunkState.REPLAY) {
            return PreparationResult.replay(response(
                jobId,
                attemptId,
                documentVersion.getId(),
                existingChunkCount(documentVersion.getId())
            ));
        }
        if (chunkState == ChunkState.NOT_ALLOWED) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_CHUNKING_NOT_ALLOWED);
        }

        // 4. 최초 UPLOADED 요청만 PARSING 상태와 시작 이벤트를 같은 Transaction에 기록한다.
        if (documentVersion.getStatus() == DocumentVersionStatus.UPLOADED) {
            documentVersion.markParsing();
            indexingEventRepository.save(IndexingEvent.builder()
                .embeddingJob(embeddingJob)
                .eventType(IndexingEventType.PARSE_STARTED)
                .fromStatus(DocumentVersionStatus.UPLOADED.name())
                .toStatus(DocumentVersionStatus.PARSING.name())
                .message(PARSE_STARTED_MESSAGE)
                .occurredAt(preparedAt)
                .build());
        }

        FileObject fileObject = documentVersion.getFileObject();
        return PreparationResult.work(new FileSnapshot(
            documentVersion.getId(),
            documentVersion.getDocument().getDocumentType(),
            new StoredFile(fileObject.getBucketName(), fileObject.getObjectKey())
        ));
    }

    /**
     * 계산된 Draft를 현재 실행 소유권으로 검증한 뒤 Version Chunk Set으로 원자 저장한다.
     */
    public ChunkResult complete(
        Long jobId,
        Long attemptId,
        Long workerId,
        String claimToken,
        Long preparedDocumentVersionId,
        List<DocumentChunkDraft> drafts
    ) {
        // 1. 외부 작업 중 소유권 교체나 Lease 만료를 차단하도록 Job과 Attempt를 다시 검증한다.
        EmbeddingJob embeddingJob = findLockedJob(jobId);
        LocalDateTime completedAt = LocalDateTime.now(clock);
        ownershipValidator.validate(embeddingJob, workerId, claimToken, completedAt);
        validateAttempt(embeddingJob, attemptId, workerId, claimToken);

        // 2. 준비 단계와 같은 Job Version을 잠그고 다른 대상에 결과가 저장되는 것을 막는다.
        DocumentVersion documentVersion = findLockedJobVersion(embeddingJob);
        if (!Objects.equals(documentVersion.getId(), preparedDocumentVersionId)) {
            throw new DocGridException(ErrorCode.DOCUMENT_FILE_REFERENCE_MISSING);
        }

        // 3. 동시 요청이 먼저 완료했으면 기존 결과를 재생하고 새 Insert나 이벤트를 만들지 않는다.
        ChunkState chunkState = resolveChunkState(documentVersion);
        if (chunkState == ChunkState.REPLAY) {
            return replayResult(jobId, attemptId, documentVersion);
        }
        if (chunkState != ChunkState.READY || documentVersion.getStatus() != DocumentVersionStatus.PARSING) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_CHUNKING_NOT_ALLOWED);
        }

        // 4. Draft 계약을 검증하고 모두 같은 Version의 불변 Entity로 변환한다.
        List<DocumentChunk> chunks = toEntities(documentVersion, drafts);
        documentChunkRepository.saveAllAndFlush(chunks);

        // 5. Chunk 전체 저장, CHUNKED 전이와 완료 이벤트를 같은 Transaction에서 Commit한다.
        documentVersion.markChunked();
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.CHUNKED)
            .fromStatus(DocumentVersionStatus.PARSING.name())
            .toStatus(DocumentVersionStatus.CHUNKED.name())
            .message(CHUNKED_MESSAGE + " versionId=" + documentVersion.getId() + ", chunkCount=" + chunks.size())
            .occurredAt(completedAt)
            .build());

        return new ChunkResult(
            response(jobId, attemptId, documentVersion.getId(), chunks.size()),
            true
        );
    }

    private EmbeddingJob findLockedJob(Long jobId) {
        return embeddingJobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));
    }

    private DocumentVersion findLockedJobVersion(EmbeddingJob embeddingJob) {
        if (embeddingJob.getDocumentVersion() == null || embeddingJob.getDocumentVersion().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_FILE_REFERENCE_MISSING);
        }
        return documentVersionRepository.findByIdForUpdate(embeddingJob.getDocumentVersion().getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_FILE_REFERENCE_MISSING));
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

    private void validateSupportedFile(DocumentVersion documentVersion) {
        if (documentVersion.getDocument() == null
            || documentVersion.getDocument().getDocumentType() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_FILE_REFERENCE_MISSING);
        }

        FileObject fileObject = documentVersion.getFileObject();
        if (fileObject == null
            || !StringUtils.hasText(fileObject.getBucketName())
            || !StringUtils.hasText(fileObject.getObjectKey())) {
            throw new DocGridException(ErrorCode.DOCUMENT_FILE_REFERENCE_MISSING);
        }

        String contentType = documentVersion.getContentType();
        if (!StringUtils.hasText(contentType)) {
            contentType = fileObject.getContentType();
        }
        if (!StringUtils.hasText(contentType)
            || !allowedContentTypes(documentVersion.getDocument().getDocumentType())
                .contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new DocGridException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
        }
    }

    private Set<String> allowedContentTypes(DocumentType documentType) {
        if (documentType == DocumentType.TXT) {
            return TXT_CONTENT_TYPES;
        }
        if (documentType == DocumentType.MD) {
            return MARKDOWN_CONTENT_TYPES;
        }
        if (documentType == DocumentType.PDF) {
            return PDF_CONTENT_TYPES;
        }
        if (documentType == DocumentType.DOCX) {
            return DOCX_CONTENT_TYPES;
        }
        return Set.of();
    }

    private ChunkState resolveChunkState(DocumentVersion documentVersion) {
        boolean chunksExist = documentChunkRepository.existsByDocumentVersionId(documentVersion.getId());
        if (documentVersion.getStatus() == DocumentVersionStatus.CHUNKED) {
            if (!chunksExist) {
                throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
            }
            return ChunkState.REPLAY;
        }
        if (chunksExist) {
            throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
        }
        if (documentVersion.getStatus() == DocumentVersionStatus.UPLOADED
            || documentVersion.getStatus() == DocumentVersionStatus.PARSING) {
            return ChunkState.READY;
        }
        return ChunkState.NOT_ALLOWED;
    }

    private List<DocumentChunk> toEntities(
        DocumentVersion documentVersion,
        List<DocumentChunkDraft> drafts
    ) {
        if (drafts == null || drafts.isEmpty()) {
            throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
        }

        List<DocumentChunk> chunks = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            DocumentChunkDraft draft = drafts.get(index);
            validateDraft(draft, index);
            chunks.add(DocumentChunk.builder()
                .documentVersion(documentVersion)
                .chunkIndex(draft.chunkIndex())
                .chunkText(draft.chunkText())
                .tokenCount(draft.tokenCount())
                .charStart(draft.charStart())
                .charEnd(draft.charEnd())
                .pageNo(draft.pageNo())
                .sectionTitle(draft.sectionTitle())
                .contentHash(draft.contentHash())
                .metadataJson(draft.metadataJson())
                .build());
        }
        return chunks;
    }

    private void validateDraft(DocumentChunkDraft draft, int expectedIndex) {
        if (draft == null
            || draft.chunkIndex() != expectedIndex
            || draft.chunkText() == null
            || draft.chunkText().isEmpty()
            || draft.tokenCount() < 0
            || draft.charStart() < 0
            || draft.charEnd() <= draft.charStart()
            || (draft.pageNo() != null && draft.pageNo() <= 0)
            || (draft.sectionTitle() != null && draft.sectionTitle().length() > 500)
            || !StringUtils.hasText(draft.contentHash())
            || !draft.contentHash().matches("[0-9a-f]{64}")) {
            throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
        }
    }

    private ChunkResult replayResult(Long jobId, Long attemptId, DocumentVersion documentVersion) {
        return new ChunkResult(
            response(jobId, attemptId, documentVersion.getId(), existingChunkCount(documentVersion.getId())),
            false
        );
    }

    private int existingChunkCount(Long documentVersionId) {
        long chunkCount = documentChunkRepository.countByDocumentVersionId(documentVersionId);
        if (chunkCount <= 0) {
            throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
        }
        return Math.toIntExact(chunkCount);
    }

    private DocumentChunksResponse response(
        Long jobId,
        Long attemptId,
        Long documentVersionId,
        int chunkCount
    ) {
        return new DocumentChunksResponse(
            jobId,
            attemptId,
            documentVersionId,
            chunkCount,
            DocumentVersionStatus.CHUNKED
        );
    }

    private enum ChunkState {
        READY,
        REPLAY,
        NOT_ALLOWED
    }

    /**
     * 외부 작업에 필요한 Version 식별자, 문서 형식과 저장 위치의 불변 Snapshot.
     */
    public record FileSnapshot(
        Long documentVersionId,
        DocumentType documentType,
        StoredFile storedFile
    ) {
    }

    /**
     * 준비 Transaction의 외부 작업 Snapshot 또는 완료 결과 중 하나를 전달한다.
     */
    public record PreparationResult(
        FileSnapshot fileSnapshot,
        ChunkResult replayResult
    ) {

        static PreparationResult work(FileSnapshot fileSnapshot) {
            return new PreparationResult(fileSnapshot, null);
        }

        static PreparationResult replay(DocumentChunksResponse response) {
            return new PreparationResult(null, new ChunkResult(response, false));
        }
    }

    /**
     * Chunk 응답과 이번 호출이 실제 Chunk Set을 처음 저장했는지를 함께 전달한다.
     */
    public record ChunkResult(
        DocumentChunksResponse response,
        boolean created
    ) {
    }
}
