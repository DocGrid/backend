package com.opensource.docgrid.domain.search.dto.response;

import java.util.List;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record SearchResponse(
    @Schema(description = "검색 요청 ID (search_queries.id)") Long queryId,
    @Schema(description = "검색 결과 목록 (유사도 내림차순)") List<SearchResultItem> results,
    @Schema(description = "RAG 답변 생성 상태 — PROCESSING이면 answer가 아직 null이라는 뜻이며, "
        + "GET /search/{queryId}로 재조회하거나 WebSocket(/user/queue/rag-answer) 알림을 기다려야 한다.")
    ResultStatus ragStatus,
    @Schema(description = "RAG로 생성된 답변, 아직 생성 전이면 null") String answer,
    @Schema(description = "답변의 근거 출처 목록") List<CitationResponse> citations
) {
    public static SearchResponse of(Long queryId, List<VectorSearchCandidate> candidates) {
        List<SearchResultItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            items.add(SearchResultItem.of(i + 1, candidates.get(i)));
        }
        return new SearchResponse(queryId, List.copyOf(items), ResultStatus.PROCESSING, null, List.of());
    }

    public static SearchResponse empty(Long queryId) {
        return new SearchResponse(queryId, List.of(), ResultStatus.SUCCESS, null, List.of());
    }

    public SearchResponse withAnswer(ResultStatus ragStatus, String answer, List<CitationResponse> citations) {
        return new SearchResponse(queryId, results, ragStatus, answer, citations);
    }
}
