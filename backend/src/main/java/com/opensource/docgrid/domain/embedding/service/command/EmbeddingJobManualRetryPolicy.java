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
     *
     * @return 첫 번째 차단 사유 또는 기본 불변식을 모두 만족한 {@code ELIGIBLE}
     */
    public EmbeddingJobManualRetryEligibility evaluateInvariant(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        Document document
    ) {
        // 1. 자동 처리 중이거나 이미 완료된 Job은 관리자가 수동으로 다시 Queue에 넣지 못하게 한다.
        if (embeddingJob.getStatus() != EmbeddingJobStatus.FAILED) {
            return EmbeddingJobManualRetryEligibility.JOB_NOT_FAILED;
        }

        // 2. Job과 Version이 함께 실패한 상태인지 확인해 두 Aggregate의 상태 불일치를 드러낸다.
        if (documentVersion.getStatus() != DocumentVersionStatus.FAILED) {
            return EmbeddingJobManualRetryEligibility.VERSION_NOT_FAILED;
        }

        // 3. 삭제되었거나 인덱싱 대상이 아닌 상태의 문서는 재처리 대상에서 제외한다.
        if (document.getDeletedAt() != null) {
            return EmbeddingJobManualRetryEligibility.DOCUMENT_DELETED;
        }
        if (!RETRYABLE_DOCUMENT_STATUSES.contains(document.getStatus())) {
            return EmbeddingJobManualRetryEligibility.DOCUMENT_STATUS_INVALID;
        }

        // 4. Document가 가리키는 현재 Version이 실제로 같은 Document에 속하는지 검증한다.
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
     *
     * @return 첫 번째 차단 사유 또는 모든 재처리 조건을 만족한 {@code ELIGIBLE}
     */
    public EmbeddingJobManualRetryEligibility evaluate(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        Document document,
        Long latestVersionId,
        boolean liveJobExists
    ) {
        // 1. Repository 추가 조회와 무관한 상태 불변식을 먼저 검사한다.
        EmbeddingJobManualRetryEligibility invariant = evaluateInvariant(
            embeddingJob,
            documentVersion,
            document
        );
        if (invariant != EmbeddingJobManualRetryEligibility.ELIGIBLE) {
            return invariant;
        }

        // 2. 최신 Version 조회 결과가 없으면 데이터 정합성 문제로 분류한다.
        if (latestVersionId == null) {
            return EmbeddingJobManualRetryEligibility.DATA_INCONSISTENT;
        }

        // 3. 실패 Job의 Version이 더 이상 최신이 아니면 과거 내용의 재인덱싱을 막는다.
        if (!Objects.equals(latestVersionId, documentVersion.getId())) {
            return EmbeddingJobManualRetryEligibility.SUPERSEDED_VERSION;
        }

        // 4. 이미 대기·처리 중인 Job이 있으면 같은 Version의 중복 Queue 투입을 막는다.
        if (liveJobExists) {
            return EmbeddingJobManualRetryEligibility.LIVE_JOB_EXISTS;
        }
        return EmbeddingJobManualRetryEligibility.ELIGIBLE;
    }
}
