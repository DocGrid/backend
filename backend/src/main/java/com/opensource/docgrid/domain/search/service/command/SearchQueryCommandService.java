package com.opensource.docgrid.domain.search.service.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.user.entity.User;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class SearchQueryCommandService {

    private final SearchQueryRepository searchQueryRepository;

    public SearchQuery createProcessing(
        User user,
        DocumentCollection collection,
        String queryText,
        EmbeddingModel model,
        float[] vector,
        int topK
    ) {
        SearchQuery searchQuery = SearchQuery.builder()
            .user(user)
            .collection(collection)
            .queryText(queryText)
            .queryEmbeddingModel(model)
            .queryVector(vector)
            .searchType(SearchType.VECTOR)
            .topK(topK)
            .status(ResultStatus.PROCESSING)
            .build();
        return searchQueryRepository.save(searchQuery);
    }

    public void markSuccess(SearchQuery searchQuery, int latencyMs) {
        searchQuery.updateToSuccess(latencyMs);
    }

    // REQUIRES_NEW: Facade에서 예외 재전파로 롤백돼도 FAILED 상태가 독립 트랜잭션으로 저장된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(SearchQuery searchQuery, String errorMessage) {
        searchQuery.updateToFailed(errorMessage);
    }
}
