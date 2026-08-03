package com.opensource.docgrid.domain.embedding.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 현재 Embedding Job Claim의 갱신된 Lease 결과를 반환하는 응답 DTO.
 *
 * <p>Worker가 다음 갱신 시점을 결정하는 데 필요한 식별자와 시각만 제공한다. 소유권 증명 값인 Claim
 * Token과 내부 Job·Worker 상태는 응답 경계 밖에 유지한다.
 */
public record RenewedEmbeddingJobLeaseResponse(
    @Schema(description = "갱신된 Embedding Job 식별자", example = "101")
    Long jobId,

    @Schema(description = "현재 Job을 소유한 Worker 식별자", example = "7")
    Long workerId,

    @Schema(description = "Lease 갱신 기준 시각", example = "2026-08-03T15:00:00")
    LocalDateTime renewedAt,

    @Schema(description = "갱신된 Lease 만료 시각", example = "2026-08-03T15:05:00")
    LocalDateTime lockExpiresAt
) {
}
