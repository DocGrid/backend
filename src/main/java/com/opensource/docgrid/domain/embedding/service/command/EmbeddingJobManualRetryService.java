package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobConverter;
import com.opensource.docgrid.domain.embedding.dto.response.ManualRetriedIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 자동 재시도를 마치고 최종 실패로 종결된 Embedding Job을 관리자 요청으로 다시 Queue에 넣는 Command Service.
 *
 * <p>Claim은 PENDING만, Lease 만료 복구는 PROCESSING만 대상으로 하므로 FAILED Job을 다시 처리할 수
 * 있는 경로는 이 Service뿐이다. Job 행을 먼저 잠가 Claim·완료·실패·복구와의 경쟁을 직렬화하고,
 * 기존 경로와 같은 Job → Version → Document 잠금 순서로 대상 조건을 검증한 뒤 Job, Version, Document
 * 상태와 감사 Event를 하나의 Transaction에서 확정한다. 외부 I/O는 수행하지 않는다.
 *
 * <p>현재 검색 가능한 이전 Version은 그대로 보존하고, 이미 STALE로 전환된 대상 Version의 Embedding만
 * 삭제한다. Chunk Set과 Attempt 이력, retryCount는 삭제하거나 초기화하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EmbeddingJobManualRetryService {

    private static final String MANUAL_RETRY_MESSAGE =
        "관리자 요청으로 최종 실패한 Embedding Job을 다시 Queue에 넣었습니다.";
    // 같은 Version에 이미 살아 있는 Job이 있으면 같은 대상이 두 번 처리되므로 재처리를 거부한다.
    private static final Set<EmbeddingJobStatus> LIVE_JOB_STATUSES = EnumSet.of(
        EmbeddingJobStatus.PENDING,
        EmbeddingJobStatus.PROCESSING
    );
    // 재처리 후 완료 Transaction이 다시 확정할 수 있는 문서 상태만 허용한다.
    private static final Set<DocumentStatus> RETRYABLE_DOCUMENT_STATUSES = EnumSet.of(
        DocumentStatus.UPLOADED,
        DocumentStatus.INDEXING,
        DocumentStatus.INDEXED,
        DocumentStatus.FAILED
    );

    private final EmbeddingJobRepository embeddingJobRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final EmbeddingJobConverter embeddingJobConverter;
    private final Clock clock;

    /**
     * 최종 실패한 Job 한 건을 즉시 Claim 가능한 PENDING 상태로 되돌린다.
     *
     * @param jobId 수동 재처리할 Embedding Job 식별자
     * @return 재처리 후 Queue 상태와 실제 재개 지점
     */
    public ManualRetriedIndexingJobResponse retry(Long jobId) {
        // 1. Claim·완료·실패·복구 경쟁을 Job 행에서 직렬화하고 재처리 기준 시각을 고정한다.
        EmbeddingJob embeddingJob = findLockedJob(jobId);
        // PostgreSQL TIMESTAMP 정밀도와 맞춰 응답 시각과 Event 기록 시각을 동일하게 유지한다.
        LocalDateTime requeuedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);

        // 2. 처리 중이거나 자동 재시도가 예정된 Job과 이미 재처리된 중복 요청을 함께 거부한다.
        if (embeddingJob.getStatus() != EmbeddingJobStatus.FAILED) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED);
        }

        // 3. 기존 완료·실패 경로와 같은 잠금 순서를 유지한 뒤 재처리 대상 조건을 검증한다.
        DocumentVersion documentVersion = findLockedVersion(embeddingJob);
        Document document = findLockedDocument(documentVersion);
        validateRetryTarget(documentVersion, document);

        // 4. 저장된 Chunk Set 유무로 재개 지점을 정하고 검색에서 제외된 대상 Embedding만 제거한다.
        DocumentVersionStatus resumeStatus = resolveResumeStatus(documentVersion);
        int deletedEmbeddingCount = embeddingRepository
            .deleteByDocumentVersionId(documentVersion.getId());

        // 5. Job, Version, Document 상태와 감사 Event를 같은 Transaction과 시각으로 확정한다.
        embeddingJob.requeueForManualRetry();
        documentVersion.reopenFailedForRetry(resumeStatus);
        restoreDocumentStatus(document, documentVersion);
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.MANUAL_RETRY)
            .fromStatus(EmbeddingJobStatus.FAILED.name())
            .toStatus(EmbeddingJobStatus.PENDING.name())
            .message(MANUAL_RETRY_MESSAGE)
            .metadataJson(manualRetryMetadata(embeddingJob, resumeStatus, deletedEmbeddingCount))
            .occurredAt(requeuedAt)
            .build());

        log.info(
            "인덱싱 Job 수동 재처리: jobId={}, documentId={}, versionId={}, resumeStatus={}, "
                + "retryCount={}, deletedEmbeddingCount={}",
            embeddingJob.getId(),
            document.getId(),
            documentVersion.getId(),
            resumeStatus,
            embeddingJob.getRetryCount(),
            deletedEmbeddingCount
        );
        return embeddingJobConverter.toManualRetriedResponse(
            embeddingJob,
            documentVersion,
            requeuedAt
        );
    }

    private EmbeddingJob findLockedJob(Long jobId) {
        return embeddingJobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));
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

    private void validateRetryTarget(DocumentVersion documentVersion, Document document) {
        // 1. 최종 실패 Job의 Version은 반드시 FAILED여야 하며 그렇지 않으면 종료 데이터가 깨진 상태다.
        if (documentVersion.getStatus() != DocumentVersionStatus.FAILED) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }

        // 2. 삭제됐거나 다시 인덱싱을 확정할 수 없는 문서는 재처리 대상이 아니다.
        if (document.getDeletedAt() != null
            || !RETRYABLE_DOCUMENT_STATUSES.contains(document.getStatus())) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID);
        }

        // 3. 현재 Version 포인터가 다른 문서를 가리키면 검색 보호 판단 자체를 신뢰할 수 없다.
        DocumentVersion currentVersion = document.getCurrentVersion();
        if (currentVersion == null
            || currentVersion.getId() == null
            || currentVersion.getDocument() == null
            || !Objects.equals(currentVersion.getDocument().getId(), document.getId())) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }

        // 4. 더 새로운 Version이 올라온 뒤라면 과거 Version을 다시 인덱싱해도 완료할 수 없다.
        DocumentVersion latestVersion = documentVersionRepository
            .findTopByDocumentIdOrderByVersionNoDesc(document.getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT));
        if (!Objects.equals(latestVersion.getId(), documentVersion.getId())) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID);
        }

        // 5. 같은 Version을 처리 중이거나 대기 중인 Job이 있으면 중복 처리가 되므로 거부한다.
        if (embeddingJobRepository.countByDocumentVersionIdAndStatusIn(
            documentVersion.getId(),
            LIVE_JOB_STATUSES
        ) > 0) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID);
        }
    }

    /**
     * 이미 저장된 Chunk Set이 있으면 파싱 단계를 생략하도록 재개 지점을 결정한다.
     *
     * <p>Chunk Set은 저장과 CHUNKED 전이가 같은 Transaction에서 일어나므로 존재하면 항상 완전하다.
     */
    private DocumentVersionStatus resolveResumeStatus(DocumentVersion documentVersion) {
        return documentChunkRepository.existsByDocumentVersionId(documentVersion.getId())
            ? DocumentVersionStatus.CHUNKED
            : DocumentVersionStatus.UPLOADED;
    }

    private void restoreDocumentStatus(Document document, DocumentVersion retriedVersion) {
        // 이전 INDEXED Version이 현재 검색 대상이면 포인터와 문서 상태를 그대로 보존한다.
        if (document.getStatus() == DocumentStatus.INDEXED
            && !Objects.equals(document.getCurrentVersion().getId(), retriedVersion.getId())) {
            return;
        }
        // 그 밖의 경우 검색 가능한 Version이 없으므로 완료 Transaction이 확정할 수 있는 처리 중 상태로 되돌린다.
        document.markIndexing();
    }

    private String manualRetryMetadata(
        EmbeddingJob embeddingJob,
        DocumentVersionStatus resumeStatus,
        int deletedEmbeddingCount
    ) {
        return """
            {"resumeVersionStatus":"%s","retryCount":%d,"maxRetryCount":%d,"deletedEmbeddingCount":%d}
            """.formatted(
                resumeStatus,
                embeddingJob.getRetryCount(),
                embeddingJob.getMaxRetryCount(),
                deletedEmbeddingCount
            ).strip();
    }
}
