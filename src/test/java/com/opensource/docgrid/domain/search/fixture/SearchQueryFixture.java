package com.opensource.docgrid.domain.search.fixture;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;

public class SearchQueryFixture {

    public static final String QUERY_TEXT = "검색 테스트 질문";
    public static final int TOP_K = 5;
    public static final float[] VECTOR = new float[1024];

    private SearchQueryFixture() {
    }

    public static SearchQuery createProcessing() {
        EmbeddingModel model = EmbeddingModelFixture.createDefaultModel();
        return SearchQuery.builder()
            .queryText(QUERY_TEXT)
            .queryEmbeddingModel(model)
            .queryVector(VECTOR)
            .searchType(SearchType.VECTOR)
            .topK(TOP_K)
            .status(ResultStatus.PROCESSING)
            .build();
    }
}
