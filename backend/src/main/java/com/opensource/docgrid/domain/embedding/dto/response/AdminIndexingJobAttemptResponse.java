package com.opensource.docgrid.domain.embedding.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.worker.enums.AttemptStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자가 확인할 수 있는 Embedding Job Attempt 실행 이력 응답이다.
 *
 * <p>실행 Worker와 종료 결과를 제공하되 과거 Claim Token과 내부 오류 메시지는 포함하지 않는다.
 */
public record AdminIndexingJobAttemptResponse(
    @Schema(description = "Attempt 식별자", example = "21")
    Long attemptId,

    @Schema(description = "Job 내부 Attempt 번호", example = "2")
    int attemptNo,

    @Schema(description = "Attempt 상태", example = "FAILED")
    AttemptStatus status,

    @Schema(description = "Attempt를 실행한 Worker 식별자")
    Long workerId,

    @Schema(description = "Attempt를 실행한 Worker 이름")
    String workerName,

    @Schema(description = "Attempt 시작 시각")
    LocalDateTime startedAt,

    @Schema(description = "Attempt 종료 시각")
    LocalDateTime endedAt,

    @Schema(description = "Attempt 소요 시간(ms)", example = "1250")
    Long durationMs,

    @Schema(description = "공개 가능한 오류 코드")
    String errorCode
) {
}
