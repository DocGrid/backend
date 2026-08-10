package com.opensource.docgrid.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record DashboardSummaryResponse(
    @Schema(description = "문서 현황")
    DocumentsSummaryResponse documents,

    @Schema(description = "인덱싱 작업 현황")
    JobsSummaryResponse jobs,

    @Schema(description = "Worker 현황")
    WorkersSummaryResponse workers,

    @Schema(description = "검색 현황")
    SearchSummaryResponse search
) {
}
