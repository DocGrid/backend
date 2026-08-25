package com.opensource.docgrid.domain.sync.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.sync.config.SyncReconciliationProperties;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;

import lombok.RequiredArgsConstructor;

/**
 * 한 DocumentVersion의 원장 상태와 Job·Chunk·Vector 파생 상태를 읽기 전용으로 비교한다.
 *
 * <p>검사는 데이터 변경을 수행하지 않으며, 안전한 자동복구 가능 여부를 Observation에 표시한다.
 * currentVersion 모순, 기존 Vector 행의 상태 손상과 삭제 잔여물은 항상 보고 전용이다.
 */
@Component
@RequiredArgsConstructor
public class SyncConsistencyInspector {

    private static final Set<DocumentVersionStatus> PROCESSING_STATUSES = EnumSet.of(
        DocumentVersionStatus.UPLOADED,
        DocumentVersionStatus.PARSING,
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING
    );
    private static final Set<EmbeddingJobStatus> LIVE_JOB_STATUSES = EnumSet.of(
        EmbeddingJobStatus.PENDING,
        EmbeddingJobStatus.PROCESSING
    );

    private final EmbeddingJobRepository embeddingJobRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final SyncReconciliationProperties properties;

    public List<SyncConsistencyObservation> inspect(
        DocumentVersion version,
        EmbeddingModel activeModel,
        LocalDateTime inspectedAt
    ) {
        Document document = version.getDocument();
        List<SyncConsistencyObservation> observations = new ArrayList<>();
        long chunkCount = documentChunkRepository.countByDocumentVersionId(version.getId());
        long activeModelEmbeddingCount = embeddingRepository
            .countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
                version.getId(), activeModel.getId(), EmbeddingStatus.ACTIVE
            );
        boolean liveJobExists = embeddingJobRepository.existsByDocumentVersionIdAndStatusIn(
            version.getId(), LIVE_JOB_STATUSES
        );

        inspectMissingJob(version, activeModel, liveJobExists, observations);
        inspectChunksAndEmbeddings(version, activeModel, chunkCount, activeModelEmbeddingCount, observations);
        inspectCurrentVersion(document, version, activeModel, observations);
        inspectStalledVersion(version, activeModel, liveJobExists, inspectedAt, observations);
        inspectDeletedResidue(document, version, activeModel, observations);
        return observations;
    }

    private void inspectMissingJob(
        DocumentVersion version,
        EmbeddingModel activeModel,
        boolean liveJobExists,
        List<SyncConsistencyObservation> observations
    ) {
        if (PROCESSING_STATUSES.contains(version.getStatus()) && !liveJobExists) {
            observations.add(observation(
                SyncConsistencyIssueType.MISSING_JOB,
                SyncConsistencySeverity.ERROR,
                version,
                activeModel,
                "{\"liveJob\":true}",
                "{\"liveJob\":false,\"versionStatus\":\"%s\"}".formatted(version.getStatus()),
                true
            ));
        }
    }

    private void inspectChunksAndEmbeddings(
        DocumentVersion version,
        EmbeddingModel activeModel,
        long chunkCount,
        long embeddingCount,
        List<SyncConsistencyObservation> observations
    ) {
        if ((version.getStatus() == DocumentVersionStatus.CHUNKED
            || version.getStatus() == DocumentVersionStatus.EMBEDDING
            || version.getStatus() == DocumentVersionStatus.INDEXED)
            && chunkCount == 0) {
            observations.add(observation(
                SyncConsistencyIssueType.MISSING_CHUNKS,
                SyncConsistencySeverity.CRITICAL,
                version,
                activeModel,
                "{\"chunkCount\":\">0\"}",
                "{\"chunkCount\":0}",
                false
            ));
        }
        boolean currentVersion = version.getDocument().getCurrentVersion() != null
            && Objects.equals(version.getDocument().getCurrentVersion().getId(), version.getId());
        if (version.getStatus() == DocumentVersionStatus.INDEXED
            && currentVersion
            && chunkCount > embeddingCount) {
            long currentModelCount = embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(
                version.getId(),
                activeModel.getId()
            );
            long allModelCount = embeddingRepository.countByDocumentVersionId(version.getId());
            SyncConsistencyIssueType issueType = currentModelCount == 0 && allModelCount >= chunkCount
                ? SyncConsistencyIssueType.MODEL_MISMATCH
                : SyncConsistencyIssueType.MISSING_EMBEDDINGS;
            observations.add(observation(
                issueType,
                SyncConsistencySeverity.CRITICAL,
                version,
                activeModel,
                "{\"activeEmbeddingCount\":%d}".formatted(chunkCount),
                ("{\"activeEmbeddingCount\":%d,\"currentModelEmbeddingCount\":%d,"
                    + "\"allModelEmbeddingCount\":%d}")
                    .formatted(embeddingCount, currentModelCount, allModelCount),
                currentModelCount == embeddingCount && currentModelCount < chunkCount
            ));
        }
    }

    private void inspectCurrentVersion(
        Document document,
        DocumentVersion version,
        EmbeddingModel activeModel,
        List<SyncConsistencyObservation> observations
    ) {
        if (document.getStatus() != DocumentStatus.INDEXED) {
            return;
        }
        DocumentVersion current = document.getCurrentVersion();
        boolean missingCurrentVersion = current == null;
        boolean inspectingCurrentVersion = current != null && Objects.equals(current.getId(), version.getId());
        if (missingCurrentVersion
            || (inspectingCurrentVersion
                && (version.getStatus() != DocumentVersionStatus.INDEXED
                    || current.getDocument() == null
                    || !Objects.equals(current.getDocument().getId(), document.getId())))) {
            observations.add(observation(
                SyncConsistencyIssueType.INVALID_CURRENT_VERSION,
                SyncConsistencySeverity.CRITICAL,
                version,
                activeModel,
                "{\"currentVersionStatus\":\"INDEXED\",\"sameDocument\":true}",
                current == null
                    ? "{\"currentVersion\":null}"
                    : "{\"currentVersionStatus\":\"%s\"}".formatted(version.getStatus()),
                false
            ));
        }
    }

    private void inspectStalledVersion(
        DocumentVersion version,
        EmbeddingModel activeModel,
        boolean liveJobExists,
        LocalDateTime inspectedAt,
        List<SyncConsistencyObservation> observations
    ) {
        if (!PROCESSING_STATUSES.contains(version.getStatus())
            || version.getUpdatedAt() == null
            || !version.getUpdatedAt().isBefore(inspectedAt.minus(properties.getStalledThreshold()))) {
            return;
        }
        observations.add(observation(
            SyncConsistencyIssueType.STALLED_VERSION,
            liveJobExists ? SyncConsistencySeverity.WARNING : SyncConsistencySeverity.ERROR,
            version,
            activeModel,
            "{\"updatedAfter\":\"%s\"}".formatted(inspectedAt.minus(properties.getStalledThreshold())),
            "{\"updatedAt\":\"%s\",\"liveJob\":%s}"
                .formatted(version.getUpdatedAt(), liveJobExists),
            false
        ));
    }

    private void inspectDeletedResidue(
        Document document,
        DocumentVersion version,
        EmbeddingModel activeModel,
        List<SyncConsistencyObservation> observations
    ) {
        if (document.getStatus() == DocumentStatus.DELETED
            && document.getCurrentVersion() != null
            && Objects.equals(document.getCurrentVersion().getId(), version.getId())
            && embeddingRepository.countByDocumentIdAndStatus(document.getId(), EmbeddingStatus.ACTIVE) > 0) {
            observations.add(observation(
                SyncConsistencyIssueType.DELETED_DOCUMENT_RESIDUE,
                SyncConsistencySeverity.CRITICAL,
                version,
                activeModel,
                "{\"activeEmbeddingCount\":0}",
                "{\"activeEmbeddingCount\":\">0\"}",
                false
            ));
        }
    }

    private SyncConsistencyObservation observation(
        SyncConsistencyIssueType type,
        SyncConsistencySeverity severity,
        DocumentVersion version,
        EmbeddingModel model,
        String expectedJson,
        String actualJson,
        boolean repairable
    ) {
        String issueKey = type == SyncConsistencyIssueType.INVALID_CURRENT_VERSION
            || type == SyncConsistencyIssueType.DELETED_DOCUMENT_RESIDUE
            ? "%s:DOCUMENT:%d".formatted(type, version.getDocument().getId())
            : "%s:VERSION:%d:MODEL:%d".formatted(type, version.getId(), model.getId());
        return new SyncConsistencyObservation(
            issueKey,
            type,
            severity,
            version.getDocument(),
            version,
            model,
            expectedJson,
            actualJson,
            repairable
        );
    }
}
