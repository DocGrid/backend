package com.opensource.docgrid.domain.embedding.converter;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.dto.response.ManualRetriedIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.dto.response.RenewedEmbeddingJobLeaseResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;

/**
 * Embedding Job Entity를 API 전용 응답 DTO로 변환하는 Converter.
 *
 * <p>Controller에 Entity와 연관 Entity를 직접 노출하지 않고 Claim과 Lease 갱신 이후 Worker가
 * 필요한 식별자와 소유권 시각, 관리자 수동 재처리 결과만 전달한다.
 */
@Component
public class EmbeddingJobConverter {

    /**
     * Claim이 완료된 Job의 소유권 정보를 응답 DTO로 변환한다.
     *
     * @param embeddingJob PROCESSING 상태로 전환되고 Lease가 설정된 Job
     * @return Worker가 후속 처리에 사용할 Claim 결과
     */
    public ClaimedEmbeddingJobResponse toClaimedResponse(EmbeddingJob embeddingJob) {
        // LAZY 연관 Entity 자체는 노출하지 않고 API 계약에 필요한 식별자만 추출한다.
        return new ClaimedEmbeddingJobResponse(
            embeddingJob.getId(),
            embeddingJob.getStatus(),
            embeddingJob.getLockedByWorker().getId(),
            embeddingJob.getDocumentVersion().getId(),
            embeddingJob.getEmbeddingModel().getId(),
            embeddingJob.getClaimToken(),
            embeddingJob.getLockedAt(),
            embeddingJob.getLockExpiresAt()
        );
    }

    /**
     * 갱신된 현재 Lease를 Claim Token 없이 API 응답으로 변환한다.
     *
     * @param embeddingJob PROCESSING 상태와 현재 Worker를 유지한 갱신 대상 Job
     * @param renewedAt 갱신 Transaction이 사용한 기준 시각
     * @return Worker가 다음 갱신 시점을 결정할 수 있는 안전한 Lease 응답
     */
    public RenewedEmbeddingJobLeaseResponse toRenewedLeaseResponse(
        EmbeddingJob embeddingJob,
        LocalDateTime renewedAt
    ) {
        return new RenewedEmbeddingJobLeaseResponse(
            embeddingJob.getId(),
            embeddingJob.getLockedByWorker().getId(),
            renewedAt,
            embeddingJob.getLockExpiresAt()
        );
    }

    /**
     * 수동 재처리로 Queue에 복귀한 Job과 재개 지점을 응답 DTO로 변환한다.
     *
     * <p>재처리 직후에는 소유 Worker가 없으므로 Claim 관련 값은 담지 않고, 운영자가 확인해야 하는
     * 재개 대상과 보존된 재시도 횟수만 전달한다.
     *
     * @param embeddingJob PENDING으로 되돌아가고 소유권이 초기화된 Job
     * @param documentVersion 재개 지점 상태로 되돌아간 대상 Version
     * @param requeuedAt 재처리 Transaction이 사용한 기준 시각
     * @return 관리자에게 노출해도 안전한 수동 재처리 결과
     */
    public ManualRetriedIndexingJobResponse toManualRetriedResponse(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        LocalDateTime requeuedAt
    ) {
        return new ManualRetriedIndexingJobResponse(
            embeddingJob.getId(),
            embeddingJob.getStatus(),
            documentVersion.getDocument().getId(),
            documentVersion.getId(),
            documentVersion.getStatus(),
            embeddingJob.getRetryCount(),
            embeddingJob.getMaxRetryCount(),
            requeuedAt
        );
    }
}
