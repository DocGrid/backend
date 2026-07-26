package com.opensource.docgrid.domain.embedding.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.worker.enums.AttemptStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 생성됐거나 멱등 재생된 Embedding Job Attempt의 외부 응답 DTO.
 *
 * <p>실행 식별자와 상태만 전달하며, 소유권 증명 값인 Claim Token과 내부 오류 정보는 노출하지 않는다.
 */
public record StartedEmbeddingJobAttemptResponse(
    @Schema(description = "시작된 Attempt 식별자", example = "100")
    Long attemptId,

    @Schema(description = "Attempt가 속한 Embedding Job 식별자", example = "10")
    Long jobId,

    @Schema(description = "Job 기준 실행 시도 번호", example = "1")
    int attemptNo,

    @Schema(description = "Attempt를 실행하는 Worker 식별자", example = "1")
    Long workerId,

    @Schema(description = "Attempt 시작 상태", example = "STARTED")
    AttemptStatus status,

    @Schema(description = "Attempt 시작 시각", example = "2026-07-26T21:40:00")
    LocalDateTime startedAt
) {
}
