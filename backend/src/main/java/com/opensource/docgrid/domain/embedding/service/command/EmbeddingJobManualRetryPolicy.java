package com.opensource.docgrid.domain.embedding.service.command;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobManualRetryEligibility;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

/**
 * 관리자 수동 재처리의 조회·Command 경계가 공유하는 대상 판정 정책이다.
 *
 * <p>Entity 상태와 호출자가 같은 조회 시점에 확보한 최신 Version·활성 Job 정보를 비교해 차단 사유를
 * 반환한다. 잠금과 Repository 조회는 담당하지 않으며 Command Service가 상태 변경 직전에 다시 호출한다.
 */
@Component
public class EmbeddingJobManualRetryPolicy {

    // 조회와 Command가 같은 활성 Job 범위를 사용해야 중복 Queue 투입 여부가 일치한다.
    public static final Set<EmbeddingJobStatus> LIVE_JOB_STATUSES = Set.of(
        EmbeddingJobStatus.PENDING,
        EmbeddingJobStatus.PROCESSING
    );

    private static final Set<DocumentStatus> RETRYABLE_DOCUMENT_STATUSES = EnumSet.of(
        DocumentStatus.UPLOADED,
        DocumentStatus.INDEXING,
        DocumentStatus.INDEXED,
        DocumentStatus.FAILED
    );

    /**
     * 최신 Version과 활성 Job을 조회하기 전에 확인할 수 있는 기본 불변식을 판정한다.
     */
    public EmbeddingJobManualRetryEligibility evaluateInvariant(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        Document document
    ) {
        if (embeddingJob.getStatus() != EmbeddingJobStatus.FAILED) {
            return EmbeddingJobManualRetryEligibility.JOB_NOT_FAILED;
        }
        if (documentVersion.getStatus() != DocumentVersionStatus.FAILED) {
            return EmbeddingJobManualRetryEligibility.VERSION_NOT_FAILED;
        }
        if (document.getDeletedAt() != null) {
            return EmbeddingJobManualRetryEligibility.DOCUMENT_DELETED;
        }
        if (!RETRYABLE_DOCUMENT_STATUSES.contains(document.getStatus())) {
            return EmbeddingJobManualRetryEligibility.DOCUMENT_STATUS_INVALID;
        }

        DocumentVersion currentVersion = document.getCurrentVersion();
        if (currentVersion == null
            || currentVersion.getId() == null
            || currentVersion.getDocument() == null
            || !Objects.equals(currentVersion.getDocument().getId(), document.getId())) {
            return EmbeddingJobManualRetryEligibility.CURRENT_VERSION_INCONSISTENT;
        }
        return EmbeddingJobManualRetryEligibility.ELIGIBLE;
    }

    /**
     * 기본 불변식에 최신 Version과 활성 Job 조건을 더해 최종 가능 여부를 판정한다.
     */
    public EmbeddingJobManualRetryEligibility evaluate(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        Document document,
        Long latestVersionId,
        boolean liveJobExists
    ) {
        EmbeddingJobManualRetryEligibility invariant = evaluateInvariant(
            embeddingJob,
            documentVersion,
            document
        );
        if (invariant != EmbeddingJobManualRetryEligibility.ELIGIBLE) {
            return invariant;
        }
        if (latestVersionId == null) {
            return EmbeddingJobManualRetryEligibility.DATA_INCONSISTENT;
        }
        if (!Objects.equals(latestVersionId, documentVersion.getId())) {
            return EmbeddingJobManualRetryEligibility.SUPERSEDED_VERSION;
        }
        if (liveJobExists) {
            return EmbeddingJobManualRetryEligibility.LIVE_JOB_EXISTS;
        }
        return EmbeddingJobManualRetryEligibility.ELIGIBLE;
    }
}
