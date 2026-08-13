package com.opensource.docgrid.domain.embedding.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 조회에 필요한 Embedding Job의 현재 상태 Snapshot 응답이다.
 *
 * <p>문서·버전·모델·현재 Worker 관계와 운영 시각을 제공하되 Claim Token과 내부 오류 메시지는
 * 소유권 및 진단 정보 보호를 위해 포함하지 않는다.
 */
public record AdminIndexingJobResponse(
    @Schema(description = "Embedding Job 식별자", example = "10")
    Long jobId,

    @Schema(description = "현재 Job 상태", example = "PROCESSING")
    EmbeddingJobStatus status,

    @Schema(description = "Queue 우선순위", example = "0")
    int priority,

    @Schema(description = "현재 자동 재시도 횟수", example = "1")
    int retryCount,

    @Schema(description = "최대 자동 재시도 횟수", example = "3")
    int maxRetryCount,

    @Schema(description = "다음 자동 재시도 가능 시각")
    LocalDateTime nextRetryAt,

    @Schema(description = "문서 식별자", example = "3")
    Long documentId,

    @Schema(description = "문서 제목", example = "운영 가이드")
    String documentTitle,

    @Schema(description = "문서 버전 식별자", example = "5")
    Long documentVersionId,

    @Schema(description = "문서 버전 번호", example = "2")
    int documentVersionNo,

    @Schema(description = "문서 버전 상태", example = "EMBEDDING")
    DocumentVersionStatus documentVersionStatus,

    @Schema(description = "Embedding Model 식별자", example = "1")
    Long embeddingModelId,

    @Schema(description = "Embedding Model 이름", example = "BAAI/bge-m3")
    String embeddingModelName,

    @Schema(description = "Embedding Model 버전", example = "1")
    String embeddingModelVersion,

    @Schema(description = "현재 소유 Worker 식별자")
    Long workerId,

    @Schema(description = "현재 소유 Worker 이름")
    String workerName,

    @Schema(description = "공개 가능한 최근 오류 코드")
    String errorCode,

    @Schema(description = "현재 Claim 잠금 시각")
    LocalDateTime lockedAt,

    @Schema(description = "현재 Claim Lease 만료 시각")
    LocalDateTime lockExpiresAt,

    @Schema(description = "Job 생성 시각")
    LocalDateTime createdAt,

    @Schema(description = "최초 처리 시작 시각")
    LocalDateTime startedAt,

    @Schema(description = "처리 완료 시각")
    LocalDateTime completedAt,

    @Schema(description = "최종 실패 시각")
    LocalDateTime failedAt
) {
}
