package com.opensource.docgrid.domain.worker.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자에게 Worker 식별 정보와 Heartbeat 기준 실질 상태를 제공하는 조회 응답이다.
 *
 * <p>Job Claim Token이나 내부 오류 정보는 포함하지 않으며 Worker 운영 상태 확인에 필요한 시각만 노출한다.
 */
public record WorkerNodeResponse(
    @Schema(description = "Worker 식별자", example = "1")
    Long workerId,

    @Schema(description = "Worker 역할명", example = "indexing-worker")
    String workerName,

    @Schema(description = "Worker 프로세스 실행 식별자", example = "2f3a2d8c-1234-4abc-8def-123456789abc")
    String instanceId,

    @Schema(description = "Worker가 실행 중인 호스트 이름", example = "docgrid-api-01")
    String hostName,

    @Schema(description = "Worker가 실행 중인 호스트 IP", example = "10.0.0.12")
    String ipAddress,

    @Schema(description = "Heartbeat 기준으로 계산한 Worker 상태", example = "ACTIVE")
    WorkerStatus status,

    @Schema(description = "마지막 Heartbeat 시각", example = "2026-07-20T15:00:00")
    LocalDateTime lastHeartbeatAt,

    @Schema(description = "Worker 시작 시각", example = "2026-07-20T14:59:00")
    LocalDateTime startedAt,

    @Schema(description = "Worker 정상 종료 시각", example = "2026-07-20T16:00:00")
    LocalDateTime stoppedAt
) {
}
