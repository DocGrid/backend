package com.opensource.docgrid.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 대시보드 응답 중 검색 현황 집계 결과.
 *
 * <p>최근 24시간 검색 요청 수만 담으며, 실제 집계는 {@code DashboardQueryService}가 수행한다.
 */
public record SearchSummaryResponse(
    @Schema(description = "최근 24시간 검색 요청 수", example = "342")
    long recent24hCount
) {
}
