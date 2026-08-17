package com.opensource.docgrid.domain.search.service.query;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.repository.ResponseCitationRepository;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.dto.response.CitationResponse;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.dto.response.SearchResultItem;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 비동기 RAG 처리(#218)에서 프론트가 WebSocket push를 신호로 삼아 다시 조회하는 GET /search/{queryId}
 * 전용 조회 서비스. POST /search 시점의 SearchResponse와 같은 모양을 그대로 재조립해 반환한다 —
 * 프론트가 두 응답을 같은 타입으로 다룰 수 있게 하기 위함이다.
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class SearchAnswerQueryService {

    private final SearchQueryRepository searchQueryRepository;
    private final RagResponseRepository ragResponseRepository;
    private final SearchResultRepository searchResultRepository;
    private final ResponseCitationRepository responseCitationRepository;

    public SearchResponse getAnswer(Long queryId, Long userId) {
        SearchQuery query = searchQueryRepository.findByIdAndUser_Id(queryId, userId)
            .orElseThrow(() -> new DocGridException(ErrorCode.RAG_ANSWER_NOT_FOUND));

        List<SearchResult> savedResults = searchResultRepository.findByQuery_IdOrderByRankNo(query.getId());
        List<SearchResultItem> items = new ArrayList<>();
        for (int i = 0; i < savedResults.size(); i++) {
            items.add(SearchResultItem.of(i + 1, VectorSearchCandidate.from(savedResults.get(i))));
        }

        RagResponse ragResponse = ragResponseRepository.findByQuery_Id(query.getId()).orElse(null);
        if (ragResponse == null || ragResponse.getStatus() == ResultStatus.PROCESSING) {
            return new SearchResponse(queryId, items, ResultStatus.PROCESSING, null, List.of());
        }

        List<CitationResponse> citations = responseCitationRepository
            .findByResponse_IdOrderByCitationOrder(ragResponse.getId())
            .stream()
            .map(CitationResponse::from)
            .toList();

        return new SearchResponse(queryId, items, ragResponse.getStatus(), ragResponse.getAnswerText(), citations);
    }
}
