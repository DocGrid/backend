package com.opensource.docgrid.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record SearchSummaryResponse(
    @Schema(description = "최근 24시간 검색 요청 수", example = "342")
    long recent24hCount
) {
}
