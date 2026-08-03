package com.opensource.docgrid.domain.embedding.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 최초 실패 또는 멱등 재생된 Attempt의 불변 종료 정보를 전달한다.
 *
 * <p>후속 Retry와 완료로 바뀔 수 있는 현재 Job 상태는 제외하고, 종료된 Attempt에 저장된 값만 반환해
 * 같은 실패 요청의 장기 멱등 응답을 보장한다.
 */
public record DocumentIndexingFailureResponse(
    @Schema(description = "실패가 보고된 Embedding Job 식별자", example = "10")
    Long jobId,

    @Schema(description = "종료된 Attempt 식별자", example = "21")
    Long attemptId,

    @Schema(description = "Job 안의 실행 시도 번호", example = "2")
    int attemptNo,

    @Schema(description = "종료된 Attempt 상태", example = "FAILED")
    AttemptStatus attemptStatus,

    @Schema(description = "저장된 인덱싱 실패 유형", example = "EMBEDDING_PROVIDER_UNAVAILABLE")
    IndexingFailureType failureType,

    @Schema(description = "최초 실패 종료 시각", example = "2026-08-03T10:30:00")
    LocalDateTime failedAt,

    @Schema(description = "Attempt 시작부터 실패까지 걸린 시간(ms)", example = "42031")
    long durationMs
) {
}
