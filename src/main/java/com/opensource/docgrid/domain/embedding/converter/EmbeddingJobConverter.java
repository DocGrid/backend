package com.opensource.docgrid.domain.embedding.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;

@Component
public class EmbeddingJobConverter {

    public ClaimedEmbeddingJobResponse toClaimedResponse(EmbeddingJob embeddingJob) {
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
