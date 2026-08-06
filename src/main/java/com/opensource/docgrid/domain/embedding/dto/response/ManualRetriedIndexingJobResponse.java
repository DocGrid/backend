package com.opensource.docgrid.domain.embedding.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 최종 실패한 Embedding Job을 관리자가 수동 재처리했을 때 반환되는 응답 DTO.
 *
 * <p>재처리 후 Queue 상태와 실제 재개 지점을 확인할 수 있는 값만 전달한다. Claim Token, 실패 원인
 * 상세와 내부 오류 정보는 포함하지 않는다.
 */
public record ManualRetriedIndexingJobResponse(
    @Schema(description = "수동 재처리한 Embedding Job 식별자", example = "10")
    Long jobId,

    @Schema(description = "재처리 후 Job 상태", example = "PENDING")
    EmbeddingJobStatus status,

    @Schema(description = "재처리 대상 문서 식별자", example = "3")
    Long documentId,

    @Schema(description = "재처리 대상 문서 버전 식별자", example = "5")
    Long documentVersionId,

    @Schema(
        description = "재처리가 다시 시작할 문서 버전 상태. Chunk가 이미 있으면 CHUNKED, 없으면 UPLOADED",
        example = "CHUNKED"
    )
    DocumentVersionStatus documentVersionStatus,

    @Schema(description = "보존된 기존 자동 재시도 횟수", example = "3")
    int retryCount,

    @Schema(description = "Job에 설정된 최대 자동 재시도 횟수", example = "3")
    int maxRetryCount,

    @Schema(description = "Queue 복귀 시각", example = "2026-08-06T15:00:00")
    LocalDateTime requeuedAt
) {
}
