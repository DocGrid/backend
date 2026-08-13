package com.opensource.docgrid.domain.search.dto.response;

import java.util.List;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;

import io.swagger.v3.oas.annotations.media.Schema;

public record SearchResponse(
    @Schema(description = "검색 요청 ID (search_queries.id)") Long queryId,
    @Schema(description = "검색 결과 목록 (유사도 내림차순)") List<SearchResultItem> results,
    @Schema(description = "RAG로 생성된 답변, 아직 생성 전이면 null") String answer,
    @Schema(description = "답변의 근거 출처 목록") List<CitationResponse> citations
) {
    public static SearchResponse of(Long queryId, List<VectorSearchCandidate> candidates) {
        List<SearchResultItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            items.add(SearchResultItem.of(i + 1, candidates.get(i)));
        }
        return new SearchResponse(queryId, List.copyOf(items), null, List.of());
    }

    public static SearchResponse empty(Long queryId) {
        return new SearchResponse(queryId, List.of(), null, List.of());
    }

    public SearchResponse withAnswer(String answer, List<CitationResponse> citations) {
        return new SearchResponse(queryId, results, answer, citations);
    }
}
