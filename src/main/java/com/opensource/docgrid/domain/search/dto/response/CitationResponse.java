package com.opensource.docgrid.domain.search.dto.response;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;

import io.swagger.v3.oas.annotations.media.Schema;

public record CitationResponse(
    @Schema(description = "인용 라벨") String label,
    @Schema(description = "출처 문서 ID") Long documentId,
    @Schema(description = "출처 문서 제목") String documentTitle,
    @Schema(description = "근거 chunk ID") Long chunkId,
    @Schema(description = "원본 문서 페이지 번호, 페이지 개념이 없는 형식은 null") Integer pageNo,
    @Schema(description = "인용된 텍스트") String quotedText
) {
    public static CitationResponse of(int order, VectorSearchCandidate candidate) {
        return new CitationResponse(
            "[" + order + "]",
            candidate.documentId(),
            candidate.documentTitle(),
            candidate.chunkId(),
            candidate.pageNo(),
            candidate.chunkText()
        );
    }
}
