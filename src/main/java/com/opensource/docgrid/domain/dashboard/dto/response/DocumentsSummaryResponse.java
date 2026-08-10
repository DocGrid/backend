package com.opensource.docgrid.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 대시보드 응답 중 문서 현황 집계 결과.
 *
 * <p>{@code Document}의 상태·Soft-delete 여부를 기준으로 계산한 값만 담으며, 실제 집계는
 * {@code DashboardQueryService}가 수행한다.
 */
public record DocumentsSummaryResponse(
    @Schema(description = "전체 문서 수 (Soft-delete 제외)", example = "25368")
    long total,

    @Schema(description = "검색 가능 문서 수 (INDEXED 상태)", example = "21742")
    long searchable,

    @Schema(description = "인덱싱 대기 중인 문서 수 (UPLOADED, INDEXING 상태)", example = "132")
    long pendingIndex
) {
}
