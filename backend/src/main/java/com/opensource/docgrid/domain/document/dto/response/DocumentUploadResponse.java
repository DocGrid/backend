package com.opensource.docgrid.domain.document.dto.response;

import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 최초 문서 업로드로 함께 생성·연결된 Document, Version, FileObject와 Embedding Job 결과를 반환한다.
 */
public record DocumentUploadResponse(
    @Schema(description = "생성된 문서 ID")
    Long documentId,
    @Schema(description = "생성된 문서 버전 ID")
    Long documentVersionId,
    @Schema(description = "저장 또는 재사용한 파일 객체 ID")
    Long fileObjectId,
    @Schema(description = "생성된 임베딩 작업 ID")
    Long embeddingJobId,
    @Schema(description = "문서 상태")
    DocumentStatus documentStatus,
    @Schema(description = "임베딩 작업 상태")
    EmbeddingJobStatus jobStatus
) {
}
