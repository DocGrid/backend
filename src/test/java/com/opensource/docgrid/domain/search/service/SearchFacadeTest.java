package com.opensource.docgrid.domain.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.embedding.dto.EmbedResult;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.service.query.QueryEmbeddingService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.fixture.SearchQueryFixture;
import com.opensource.docgrid.domain.search.service.command.SearchQueryCommandService;
import com.opensource.docgrid.domain.search.service.command.SearchResultCommandService;
import com.opensource.docgrid.domain.search.service.query.AccessibleDocumentQueryService;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;
import com.opensource.docgrid.domain.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SearchFacade 단위 테스트")
class SearchFacadeTest {

    @InjectMocks private SearchFacade searchFacade;

    @Mock private QueryEmbeddingService queryEmbeddingService;
    @Mock private SearchQueryCommandService searchQueryCommandService;
    @Mock private AccessibleDocumentQueryService accessibleDocumentQueryService;
    @Mock private VectorSearchQueryService vectorSearchQueryService;
    @Mock private PermissionQueryService permissionQueryService;
    @Mock private SearchResultCommandService searchResultCommandService;
    @Mock private UserRepository userRepository;
    @Mock private CollectionRepository collectionRepository;

    private static final Long USER_ID = 1L;
    private static final SearchRequest REQUEST = new SearchRequest("검색어", 5, null);

    @Test
    @DisplayName("정상 흐름: 후보 조회 후 live check를 거쳐 결과를 반환한다")
    void search_normalFlow_returnsResults() {
        EmbedResult embedResult = new EmbedResult(EmbeddingModelFixture.createDefaultModel(), new float[1024]);
        SearchQuery searchQuery = SearchQueryFixture.createProcessing();
        VectorSearchCandidate candidate = new VectorSearchCandidate(1L, 2L, 3L, "텍스트", 1, "제목", new BigDecimal("0.9"));

        given(queryEmbeddingService.embed(anyString())).willReturn(embedResult);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(com.opensource.docgrid.domain.user.entity.User.builder()
            .email("test@test.com").passwordHash("hash").name("테스트").build()));
        given(searchQueryCommandService.createProcessing(any(), any(), anyString(), any(), any(), anyInt()))
            .willReturn(searchQuery);
        given(accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null)).willReturn(List.of(3L));
        given(vectorSearchQueryService.search(any(), any(), any(), anyInt())).willReturn(List.of(candidate));
        given(permissionQueryService.canReadDocument(USER_ID, 3L)).willReturn(true);

        SearchResponse response = searchFacade.search(USER_ID, REQUEST);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).rank()).isEqualTo(1);
        then(searchResultCommandService).should(times(1)).saveAll(any(), any());
        then(searchQueryCommandService).should(times(1)).markSuccess(any(), anyInt());
    }

    @Test
    @DisplayName("접근 가능한 문서가 없으면 벡터 검색을 건너뛰고 빈 결과를 반환한다")
    void search_noPermittedIds_skipsVectorSearch() {
        EmbedResult embedResult = new EmbedResult(EmbeddingModelFixture.createDefaultModel(), new float[1024]);
        SearchQuery searchQuery = SearchQueryFixture.createProcessing();

        given(queryEmbeddingService.embed(anyString())).willReturn(embedResult);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(com.opensource.docgrid.domain.user.entity.User.builder()
            .email("test@test.com").passwordHash("hash").name("테스트").build()));
        given(searchQueryCommandService.createProcessing(any(), any(), anyString(), any(), any(), anyInt()))
            .willReturn(searchQuery);
        given(accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null)).willReturn(List.of());

        SearchResponse response = searchFacade.search(USER_ID, REQUEST);

        assertThat(response.results()).isEmpty();
        then(vectorSearchQueryService).should(never()).search(any(), anyLong(), any(), anyInt());
        then(searchQueryCommandService).should(times(1)).markSuccess(any(), anyInt());
    }

    @Test
    @DisplayName("live check에서 탈락한 후보는 결과에서 제외된다")
    void search_liveCheckFiltersOut_excludesCandidate() {
        EmbedResult embedResult = new EmbedResult(EmbeddingModelFixture.createDefaultModel(), new float[1024]);
        SearchQuery searchQuery = SearchQueryFixture.createProcessing();
        VectorSearchCandidate candidate = new VectorSearchCandidate(1L, 2L, 3L, "텍스트", 1, "제목", new BigDecimal("0.9"));

        given(queryEmbeddingService.embed(anyString())).willReturn(embedResult);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(com.opensource.docgrid.domain.user.entity.User.builder()
            .email("test@test.com").passwordHash("hash").name("테스트").build()));
        given(searchQueryCommandService.createProcessing(any(), any(), anyString(), any(), any(), anyInt()))
            .willReturn(searchQuery);
        given(accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null)).willReturn(List.of(3L));
        given(vectorSearchQueryService.search(any(), any(), any(), anyInt())).willReturn(List.of(candidate));
        given(permissionQueryService.canReadDocument(USER_ID, 3L)).willReturn(false);

        SearchResponse response = searchFacade.search(USER_ID, REQUEST);

        assertThat(response.results()).isEmpty();
        then(searchResultCommandService).should(times(1)).saveAll(any(), any());
        then(searchQueryCommandService).should(times(1)).markSuccess(any(), anyInt());
    }
}
