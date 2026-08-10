package com.opensource.docgrid.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record WorkersSummaryResponse(
    @Schema(description = "정상(ACTIVE·IDLE) Worker 수", example = "5")
    long activeCount,

    @Schema(description = "전체 등록 Worker 수", example = "6")
    long totalCount
) {
}
