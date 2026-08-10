package com.opensource.docgrid.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record JobsSummaryResponse(
    @Schema(description = "인덱싱 대기 작업 수 (PENDING)", example = "132")
    long pending,

    @Schema(description = "처리 중인 작업 수 (PROCESSING)", example = "8")
    long processing,

    @Schema(description = "실패 작업 수 (FAILED)", example = "27")
    long failed,

    @Schema(description = "평균 임베딩 처리 시간(ms). Queue 대기 시간은 제외한 순수 처리 시간이며, "
        + "완료된 Job이 없으면 null", example = "3200")
    Long avgProcessMs
) {
}
