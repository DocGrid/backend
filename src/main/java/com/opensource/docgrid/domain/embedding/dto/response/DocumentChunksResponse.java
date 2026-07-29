package com.opensource.docgrid.domain.embedding.dto.response;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 생성됐거나 멱등 재생된 Document Chunk Set의 식별자와 집계 상태를 전달한다.
 *
 * <p>Claim Token, 저장소 위치와 Chunk 본문은 제외하고 현재 실행 Context 및 Version 결과만 노출한다.
 */
public record DocumentChunksResponse(
    @Schema(description = "처리한 Embedding Job 식별자", example = "10")
    Long jobId,

    @Schema(description = "현재 실행 Attempt 식별자", example = "100")
    Long attemptId,

    @Schema(description = "Chunk가 저장된 Document Version 식별자", example = "5")
    Long documentVersionId,

    @Schema(description = "Version에 저장된 Chunk 수", example = "3")
    int chunkCount,

    @Schema(description = "Chunk 저장이 끝난 Version 상태", example = "CHUNKED")
    DocumentVersionStatus versionStatus
) {
}
