package com.opensource.docgrid.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FAILED 작업 전체 재처리 요청의 결과 응답.
 *
 * <p>개별 Job 재처리 실패는 건너뛰고 성공한 건수만 집계하며, 실패 원인별 상세는 포함하지 않는다.
 */
public record RetryAllJobsResponse(
    @Schema(description = "재처리에 성공한 Job 수", example = "27")
    int retriedCount,

    @Schema(description = "결과 메시지", example = "27개 작업 재처리 요청이 완료되었습니다.")
    String message
) {
}
