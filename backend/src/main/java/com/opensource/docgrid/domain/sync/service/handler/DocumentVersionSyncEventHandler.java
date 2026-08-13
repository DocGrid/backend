package com.opensource.docgrid.domain.sync.service.handler;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobManualRetryService;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.service.SyncEventHandler;
import com.opensource.docgrid.domain.sync.service.SyncEventPayloadReader;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 문서 버전 생성과 명시적 재인덱싱 Event를 기존 Embedding Job Queue에 멱등 반영한다.
 *
 * <p>최초 업로드 Transaction에서 이미 같은 sourceEventId Job이 생성됐다면 검증만 하고, 과거 장애로
 * Job이 누락된 경우에만 새 Job을 만든다. 실패 Job 재인덱싱은 기존 수동 재처리 불변식을 재사용한다.
 */
@Component
@RequiredArgsConstructor
public class DocumentVersionSyncEventHandler implements SyncEventHandler {

    private static final int DEFAULT_JOB_PRIORITY = 0;
    private static final int MAX_RETRY_COUNT = 3;
    private static final Set<SyncEventType> SUPPORTED_TYPES = EnumSet.of(
        SyncEventType.DOCUMENT_VERSION_CREATED,
        SyncEventType.DOCUMENT_REINDEX_REQUESTED
    );

    private final DocumentVersionRepository documentVersionRepository;
    private final EmbeddingModelRepository embeddingModelRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobManualRetryService embeddingJobManualRetryService;
    private final SyncEventPayloadReader payloadReader;

    @Override
    public Set<SyncEventType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public void handle(SyncOutboxEvent event) {
        DocumentVersion version = documentVersionRepository.findById(event.getAggregateId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT));
        EmbeddingModel model = embeddingModelRepository.findById(
                payloadReader.requiredLong(event, "embeddingModelId")
            )
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT));

        // 같은 sourceEventId Job은 최초 실행이나 재전달 모두 검증 후 그대로 재사용한다.
        Optional<EmbeddingJob> sourceJob = embeddingJobRepository.findBySourceEventId(event.getEventId());
        if (sourceJob.isPresent()) {
            validateJob(sourceJob.get(), version, model);
            return;
        }

        Optional<EmbeddingJob> latestJob = embeddingJobRepository
            .findTopByDocumentVersionIdAndEmbeddingModelIdOrderByIdDesc(version.getId(), model.getId());
        if (event.getEventType() == SyncEventType.DOCUMENT_REINDEX_REQUESTED && latestJob.isPresent()) {
            retryOrReuse(latestJob.get());
            return;
        }
        if (latestJob.isPresent() || version.getStatus() != DocumentVersionStatus.UPLOADED) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }

        embeddingJobRepository.save(
            EmbeddingJob.builder()
                .documentVersion(version)
                .embeddingModel(model)
                .sourceEventId(event.getEventId())
                .status(EmbeddingJobStatus.PENDING)
                .priority(DEFAULT_JOB_PRIORITY)
                .maxRetryCount(MAX_RETRY_COUNT)
                .build()
        );
    }

    private void retryOrReuse(EmbeddingJob job) {
        if (job.getStatus() == EmbeddingJobStatus.FAILED) {
            embeddingJobManualRetryService.retry(job.getId());
            return;
        }
        if (job.getStatus() == EmbeddingJobStatus.PENDING
            || job.getStatus() == EmbeddingJobStatus.PROCESSING) {
            return;
        }
        throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
    }

    private void validateJob(EmbeddingJob job, DocumentVersion version, EmbeddingModel model) {
        if (job.getDocumentVersion() == null
            || job.getEmbeddingModel() == null
            || !Objects.equals(job.getDocumentVersion().getId(), version.getId())
            || !Objects.equals(job.getEmbeddingModel().getId(), model.getId())) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
    }
}
