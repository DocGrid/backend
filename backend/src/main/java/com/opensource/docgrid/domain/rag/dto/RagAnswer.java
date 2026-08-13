package com.opensource.docgrid.domain.rag.dto;

import java.util.ArrayList;
import java.util.List;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.dto.response.CitationResponse;

/**
 * RagFacade.generate()의 반환 타입. SearchController가 이 값을 SearchResponse에 병합한다.
 */
public record RagAnswer(String answerText, List<CitationResponse> citations) {

    public static RagAnswer of(String answerText, List<VectorSearchCandidate> candidates) {
        List<CitationResponse> items = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            items.add(CitationResponse.of(i + 1, candidates.get(i)));
        }
        return new RagAnswer(answerText, List.copyOf(items));
    }

    public static RagAnswer noContext(String answerText) {
        return new RagAnswer(answerText, List.of());
    }
}
