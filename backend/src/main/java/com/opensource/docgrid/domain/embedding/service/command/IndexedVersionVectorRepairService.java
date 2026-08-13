package com.opensource.docgrid.domain.embedding.service.command;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 현재 INDEXED Version을 기존 Vector 보존 상태로 Chunk 기반 재임베딩 Queue에 되돌린다.
 *
 * <p>Version·Document 잠금 안에서 현재 검색 대상과 부분 Set을 검증하고 새 Job만 만든다. Worker는 기존
 * Vector를 덮거나 지우지 않고 누락 Chunk만 채우며, 다른 모델의 Vector도 모델 전환 이력으로 보존한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IndexedVersionVectorRepairService {

    private static final int DEFAULT_JOB_PRIORITY = 0;
    private static final int MAX_RETRY_COUNT = 3;
    private static final Set<EmbeddingJobStatus> LIVE_JOB_STATUSES = EnumSet.of(
        EmbeddingJobStatus.PENDING,
        EmbeddingJobStatus.PROCESSING
    );

    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingRepository embeddingRepository;

    public EmbeddingJob repair(
        Long documentVersionId,
        EmbeddingModel embeddingModel,
        UUID sourceEventId
    ) {
        // 1. Worker 완료 경로와 같은 Version → Document 순서로 잠그고 현재 검색 대상인지 검증한다.
        DocumentVersion version = documentVersionRepository.findByIdForUpdate(documentVersionId)
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT));
        Document document = documentRepository.findByIdForUpdate(version.getDocument().getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT));
        validateTarget(document, version, embeddingModel);

        // 2. 검색 노출을 중단하고 기존 Chunk Set을 재사용하는 상태로 원자 전환한다.
        version.reopenIndexedForVectorRepair();
        document.markIndexing();

        // 3. Repair Event를 원인으로 가진 Job을 만들어 Event 재전달에서도 한 건만 유지한다.
        return embeddingJobRepository.save(
            EmbeddingJob.builder()
                .documentVersion(version)
                .embeddingModel(embeddingModel)
                .sourceEventId(sourceEventId)
                .status(EmbeddingJobStatus.PENDING)
                .priority(DEFAULT_JOB_PRIORITY)
                .maxRetryCount(MAX_RETRY_COUNT)
                .build()
        );
    }

    private void validateTarget(
        Document document,
        DocumentVersion version,
        EmbeddingModel embeddingModel
    ) {
        if (version.getStatus() != DocumentVersionStatus.INDEXED
            || document.getStatus() != DocumentStatus.INDEXED
            || document.getCurrentVersion() == null
            || !Objects.equals(document.getCurrentVersion().getId(), version.getId())
            || !documentChunkRepository.existsByDocumentVersionId(version.getId())
            || embeddingJobRepository.existsByDocumentVersionIdAndStatusIn(
                version.getId(), LIVE_JOB_STATUSES
            )) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
        long chunkCount = documentChunkRepository.countByDocumentVersionId(version.getId());
        long allModelEmbeddingCount = embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(
            version.getId(),
            embeddingModel.getId()
        );
        long activeEmbeddingCount = embeddingRepository
            .countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
                version.getId(),
                embeddingModel.getId(),
                EmbeddingStatus.ACTIVE
            );
        if (allModelEmbeddingCount >= chunkCount
            || activeEmbeddingCount >= chunkCount
            || allModelEmbeddingCount != activeEmbeddingCount) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
    }
}
