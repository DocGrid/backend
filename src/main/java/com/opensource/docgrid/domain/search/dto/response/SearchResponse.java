package com.opensource.docgrid.domain.search.dto.response;

import java.util.List;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;

import io.swagger.v3.oas.annotations.media.Schema;

public record SearchResponse(
    @Schema(description = "검색 요청 ID (search_queries.id)") Long queryId,
    @Schema(description = "검색 결과 목록 (유사도 내림차순)") List<SearchResultItem> results
) {
    public static SearchResponse of(Long queryId, List<VectorSearchCandidate> candidates) {
        List<SearchResultItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            items.add(SearchResultItem.of(i + 1, candidates.get(i)));
        }
        return new SearchResponse(queryId, items);
    }

    public static SearchResponse empty(Long queryId) {
        return new SearchResponse(queryId, List.of());
    }
}
