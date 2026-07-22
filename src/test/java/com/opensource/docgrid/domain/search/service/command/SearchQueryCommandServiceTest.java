package com.opensource.docgrid.domain.search.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;
import com.opensource.docgrid.domain.search.fixture.SearchQueryFixture;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchQueryCommandService 단위 테스트")
class SearchQueryCommandServiceTest {

    @InjectMocks
    private SearchQueryCommandService searchQueryCommandService;

    @Mock
    private SearchQueryRepository searchQueryRepository;

    @Test
    @DisplayName("createProcessing: PROCESSING 상태와 VECTOR 타입으로 SearchQuery를 저장한다")
    void createProcessing_savesWithProcessingStatus() {
        EmbeddingModel model = EmbeddingModelFixture.createDefaultModel();
        given(searchQueryRepository.save(any(SearchQuery.class))).willAnswer(i -> i.getArgument(0));

        searchQueryCommandService.createProcessing(
            null, null, SearchQueryFixture.QUERY_TEXT,
            model, SearchQueryFixture.VECTOR, SearchQueryFixture.TOP_K
        );

        ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
        then(searchQueryRepository).should(times(1)).save(captor.capture());

        SearchQuery saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ResultStatus.PROCESSING);
        assertThat(saved.getSearchType()).isEqualTo(SearchType.VECTOR);
        assertThat(saved.getTopK()).isEqualTo(SearchQueryFixture.TOP_K);
        assertThat(saved.getQueryText()).isEqualTo(SearchQueryFixture.QUERY_TEXT);
    }

    @Test
    @DisplayName("markSuccess: status가 SUCCESS로 갱신되고 latencyMs가 저장된다")
    void markSuccess_updatesStatusAndLatency() {
        SearchQuery searchQuery = SearchQueryFixture.createProcessing();

        searchQueryCommandService.markSuccess(searchQuery, 120);

        assertThat(searchQuery.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(searchQuery.getLatencyMs()).isEqualTo(120);
    }

    @Test
    @DisplayName("markFailed: status가 FAILED로 갱신되고 errorMessage가 저장된다")
    void markFailed_updatesStatusAndErrorMessage() {
        SearchQuery searchQuery = SearchQueryFixture.createProcessing();

        searchQueryCommandService.markFailed(searchQuery, "임베딩 서버 연결 실패");

        assertThat(searchQuery.getStatus()).isEqualTo(ResultStatus.FAILED);
        assertThat(searchQuery.getErrorMessage()).isEqualTo("임베딩 서버 연결 실패");
    }
}
