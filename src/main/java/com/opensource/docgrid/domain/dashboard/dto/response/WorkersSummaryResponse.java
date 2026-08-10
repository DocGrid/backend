package com.opensource.docgrid.domain.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 대시보드 응답 중 Worker 현황 집계 결과.
 *
 * <p>Heartbeat 기준 실시간 상태 계산은 {@code WorkerNodeQueryService}에 위임하고, 이 DTO는
 * 그 결과를 센 개수만 담는다.
 */
public record WorkersSummaryResponse(
    @Schema(description = "정상(ACTIVE·IDLE) Worker 수", example = "5")
    long activeCount,

    @Schema(description = "전체 등록 Worker 수", example = "6")
    long totalCount
) {
}
