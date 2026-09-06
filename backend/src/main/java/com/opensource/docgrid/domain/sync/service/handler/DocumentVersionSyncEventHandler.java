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
import com.opensource.docgrid.domain.embedding.service.command.IndexedVersionVectorRepairService;
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
    private static final Set<DocumentVersionStatus> RECOVERABLE_PROCESSING_STATUSES = EnumSet.of(
        DocumentVersionStatus.UPLOADED,
        DocumentVersionStatus.PARSING,
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING
    );

    private final DocumentVersionRepository documentVersionRepository;
    private final EmbeddingModelRepository embeddingModelRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobManualRetryService embeddingJobManualRetryService;
    private final IndexedVersionVectorRepairService indexedVersionVectorRepairService;
    private final SyncEventPayloadReader payloadReader;

    /**
     * 이 Handler가 처리할 문서 버전 생성·재인덱싱 Event 종류를 반환한다.
     */
    @Override
    public Set<SyncEventType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * 문서 버전 Event를 기존 Job 재사용, 실패 Job 재시도, Vector 복구 또는 신규 Job 생성으로 반영한다.
     */
    @Override
    public void handle(SyncOutboxEvent event) {
        // 1. Event Aggregate와 Payload가 가리키는 버전·모델 원장을 조회한다.
        DocumentVersion version = documentVersionRepository.findById(event.getAggregateId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT));
        EmbeddingModel model = embeddingModelRepository.findById(
                payloadReader.requiredLong(event, "embeddingModelId")
            )
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT));

        // 2. 같은 sourceEventId Job이 있으면 관계를 검증하고 재전달을 멱등 성공으로 끝낸다.
        Optional<EmbeddingJob> sourceJob = embeddingJobRepository.findBySourceEventId(event.getEventId());
        if (sourceJob.isPresent()) {
            validateJob(sourceJob.get(), version, model);
            return;
        }

        // 3. 재인덱싱 Event는 같은 버전·모델의 최근 Job 상태에 맞춰 재시도·재사용·복구한다.
        Optional<EmbeddingJob> latestJob = embeddingJobRepository
            .findTopByDocumentVersionIdAndEmbeddingModelIdOrderByIdDesc(version.getId(), model.getId());
        if (event.getEventType() == SyncEventType.DOCUMENT_REINDEX_REQUESTED && latestJob.isPresent()) {
            retryOrReuse(latestJob.get(), event, version, model);
            return;
        }

        // 4. 생성 Event에서 Job만 누락된 복구 가능 상태인지 확인하고 그 외 중복·완료 상태는 거부한다.
        if (latestJob.isPresent() || !RECOVERABLE_PROCESSING_STATUSES.contains(version.getStatus())) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }

        // 5. 최초 Transaction 장애로 누락된 Job을 Event ID에 연결해 한 번만 생성한다.
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

    /**
     * 재인덱싱 대상의 최근 Job 상태에 따라 기존 작업을 재사용하거나 안전한 복구 경로를 호출한다.
     */
    private void retryOrReuse(
        EmbeddingJob job,
        SyncOutboxEvent event,
        DocumentVersion version,
        EmbeddingModel model
    ) {
        if (job.getStatus() == EmbeddingJobStatus.FAILED) {
            embeddingJobManualRetryService.retry(job.getId());
            return;
        }
        if (job.getStatus() == EmbeddingJobStatus.PENDING
            || job.getStatus() == EmbeddingJobStatus.PROCESSING) {
            return;
        }
        if (job.getStatus() == EmbeddingJobStatus.INDEXED) {
            indexedVersionVectorRepairService.repair(version.getId(), model, event.getEventId());
            return;
        }
        throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
    }

    /**
     * sourceEventId로 찾은 Job이 Event의 버전과 모델을 정확히 가리키는지 확인한다.
     */
    private void validateJob(EmbeddingJob job, DocumentVersion version, EmbeddingModel model) {
        if (job.getDocumentVersion() == null
            || job.getEmbeddingModel() == null
            || !Objects.equals(job.getDocumentVersion().getId(), version.getId())
            || !Objects.equals(job.getEmbeddingModel().getId(), model.getId())) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
    }
}
