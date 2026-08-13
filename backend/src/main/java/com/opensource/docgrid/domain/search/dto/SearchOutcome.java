package com.opensource.docgrid.domain.search.dto;

import java.util.List;

import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.entity.SearchResult;

/**
 * SearchFacade가 SearchController에게 검색 결과와 함께 넘겨주는 내부 전달용 객체 (API 응답 아님).
 *
 * <p>candidates/savedResults는 RagFacade.generate() 호출에 필요하다 — SearchResponse(API 응답)는
 * 표시용 SearchResultItem만 담고 있어 chunk/document id, 저장된 SearchResult의 id가 없다.
 */
public record SearchOutcome(
    SearchResponse response,
    List<VectorSearchCandidate> candidates,
    List<SearchResult> savedResults
) {
}
