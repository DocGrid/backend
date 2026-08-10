package com.opensource.docgrid.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * RAGOps Dashboard 집계 지표 조회의 최상위 응답 DTO.
 *
 * <p>문서·작업·Worker·검색 4개 하위 요약을 하나의 조회 시점 Snapshot으로 조합하는 경계이며,
 * 각 하위 요약의 집계 책임은 {@link DocumentsSummaryResponse}, {@link JobsSummaryResponse},
 * {@link WorkersSummaryResponse}, {@link SearchSummaryResponse}에 있다.
 */
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
