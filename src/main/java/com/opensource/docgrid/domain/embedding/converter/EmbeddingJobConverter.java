package com.opensource.docgrid.domain.embedding.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;

/**
 * Embedding Job Entity를 API 전용 응답 DTO로 변환하는 Converter.
 *
 * <p>Controller에 Entity와 연관 Entity를 직접 노출하지 않고 Claim 이후 Worker가 필요한 식별자와
 * Lease 정보만 전달한다.
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
}
