package com.opensource.docgrid.domain.embedding.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record ClaimedEmbeddingJobResponse(
    @Schema(description = "Claim한 Embedding Job 식별자", example = "10")
    Long jobId,

    @Schema(description = "Claim 후 Job 상태", example = "PROCESSING")
    EmbeddingJobStatus status,

    @Schema(description = "Job을 Claim한 Worker 식별자", example = "1")
    Long workerId,

    @Schema(description = "인덱싱 대상 문서 버전 식별자", example = "5")
    Long documentVersionId,

    @Schema(description = "Job에 고정된 임베딩 모델 식별자", example = "2")
    Long embeddingModelId,

    @Schema(description = "이번 Claim의 소유권 Token", example = "34c19d16-6ae1-4f6a-a35d-0123456789ab")
    String claimToken,

    @Schema(description = "Lease 시작 시각", example = "2026-07-22T15:00:00")
    LocalDateTime lockedAt,

    @Schema(description = "Lease 만료 시각", example = "2026-07-22T15:05:00")
    LocalDateTime lockExpiresAt
) {
}
