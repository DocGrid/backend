package com.opensource.docgrid.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FAILED 작업 전체 재처리 요청의 결과 응답.
 *
 * <p>동시 상태 변경 등 예상 가능한 대상 제외와 예상 밖 실행 오류를 분리해 운영자가 실제 처리 결과를
 * 성공 메시지만으로 오해하지 않게 한다.
 */
public record RetryAllJobsResponse(
    @Schema(description = "확인한 FAILED Job 수", example = "30")
    int scannedCount,

    @Schema(description = "재처리에 성공한 Job 수", example = "27")
    int retriedCount,

    @Schema(description = "현재 상태상 재처리 대상에서 제외된 Job 수", example = "2")
    int skippedCount,

    @Schema(description = "예상 밖 오류로 재처리하지 못한 Job 수", example = "1")
    int failedCount,

    @Schema(
        description = "결과 메시지",
        example = "재처리 27건, 대상 제외 2건, 오류 1건입니다."
    )
    String message
) {
}
