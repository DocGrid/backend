package com.opensource.docgrid.domain.embedding.dto.response;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 생성됐거나 멱등 재생된 Document Embedding Set의 식별자와 집계 상태를 전달한다.
 *
 * <p>Claim Token, Chunk Text와 Vector는 제외하고 현재 실행 Context, 고정 Model과 Version 결과만
 * 노출한다. Embedding 저장 완료 시에도 Version은 후속 색인 완료 전까지 EMBEDDING을 유지한다.
 */
public record DocumentEmbeddingsResponse(
    @Schema(description = "처리한 Embedding Job 식별자", example = "10")
    Long jobId,

    @Schema(description = "현재 실행 Attempt 식별자", example = "100")
    Long attemptId,

    @Schema(description = "Embedding이 저장된 Document Version 식별자", example = "5")
    Long documentVersionId,

    @Schema(description = "Job에 고정된 Embedding Model 식별자", example = "7")
    Long embeddingModelId,

    @Schema(description = "Version의 전체 Chunk 수", example = "3")
    int chunkCount,

    @Schema(description = "현재 Model로 저장된 Embedding 수", example = "3")
    int embeddingCount,

    @Schema(description = "Embedding 저장 후 Version 상태", example = "EMBEDDING")
    DocumentVersionStatus versionStatus
) {
}
