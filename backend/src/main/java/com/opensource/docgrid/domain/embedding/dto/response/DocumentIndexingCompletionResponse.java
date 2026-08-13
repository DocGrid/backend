package com.opensource.docgrid.domain.embedding.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 최초 완료 또는 멱등 재생된 문서 인덱싱 결과의 안정적인 실행·대상·시각 정보를 전달한다.
 *
 * <p>이후 새 Version이 활성화돼도 바뀌지 않는 완료 실행 정보만 반환하며 Claim Token, Vector와
 * 현재 Document 포인터 같은 가변 내부 상태는 노출하지 않는다.
 */
public record DocumentIndexingCompletionResponse(
    @Schema(description = "완료된 Embedding Job 식별자", example = "41")
    Long jobId,

    @Schema(description = "완료된 Attempt 식별자", example = "103")
    Long attemptId,

    @Schema(description = "인덱싱된 Document 식별자", example = "10")
    Long documentId,

    @Schema(description = "인덱싱된 Document Version 식별자", example = "22")
    Long documentVersionId,

    @Schema(description = "Job에 고정된 Embedding Model 식별자", example = "1")
    Long embeddingModelId,

    @Schema(description = "완료된 Job 상태", example = "INDEXED")
    EmbeddingJobStatus jobStatus,

    @Schema(description = "완료된 Attempt 상태", example = "SUCCESS")
    AttemptStatus attemptStatus,

    @Schema(description = "완료된 Version 상태", example = "INDEXED")
    DocumentVersionStatus versionStatus,

    @Schema(description = "최초 완료 시각", example = "2026-07-31T16:00:00")
    LocalDateTime completedAt,

    @Schema(description = "Attempt 시작부터 완료까지 걸린 시간(ms)", example = "8421")
    long durationMs
) {
}
