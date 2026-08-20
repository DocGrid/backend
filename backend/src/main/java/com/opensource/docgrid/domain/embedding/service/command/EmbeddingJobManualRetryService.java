package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

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
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobManualRetryEligibility;
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

    private final EmbeddingJobRepository embeddingJobRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final EmbeddingJobConverter embeddingJobConverter;
    private final EmbeddingJobManualRetryPolicy manualRetryPolicy;
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
        validateRetryTarget(embeddingJob, documentVersion, document);

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

    private void validateRetryTarget(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        Document document
    ) {
        // 1. 최신 Version 조회 전 확인 가능한 불변식부터 검증해 잘못된 종료 데이터를 조기에 차단한다.
        throwWhenRetryBlocked(manualRetryPolicy.evaluateInvariant(
            embeddingJob,
            documentVersion,
            document
        ));

        // 2. 더 새로운 Version과 활성 Job을 확인한 뒤 같은 정책으로 최종 대상 여부를 판정한다.
        DocumentVersion latestVersion = documentVersionRepository
            .findTopByDocumentIdOrderByVersionNoDesc(document.getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT));
        boolean liveJobExists = embeddingJobRepository.countByDocumentVersionIdAndStatusIn(
            documentVersion.getId(),
            EmbeddingJobManualRetryPolicy.LIVE_JOB_STATUSES
        ) > 0;
        throwWhenRetryBlocked(manualRetryPolicy.evaluate(
            embeddingJob,
            documentVersion,
            document,
            latestVersion.getId(),
            liveJobExists
        ));
    }

    private void throwWhenRetryBlocked(EmbeddingJobManualRetryEligibility eligibility) {
        switch (eligibility) {
            case ELIGIBLE -> {
                return;
            }
            case JOB_NOT_FAILED -> throw new DocGridException(
                ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED
            );
            case VERSION_NOT_FAILED, CURRENT_VERSION_INCONSISTENT, DATA_INCONSISTENT ->
                throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
            default -> throw new DocGridException(
                ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID
            );
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
