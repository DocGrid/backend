package com.opensource.docgrid.domain.search.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.embedding.entity.Embedding;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.fixture.SearchQueryFixture;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchResultCommandService 단위 테스트")
class SearchResultCommandServiceTest {

    @InjectMocks
    private SearchResultCommandService searchResultCommandService;

    @Mock
    private SearchResultRepository searchResultRepository;

    @Mock
    private EntityManager entityManager;

    @Test
    @DisplayName("후보 목록을 rank 순서로 search_results에 저장한다")
    void saveAll_savesWithRank() {
        SearchQuery query = SearchQueryFixture.createProcessing();
        VectorSearchCandidate c1 = candidate(1L, 10L, 100L, new BigDecimal("0.9"));
        VectorSearchCandidate c2 = candidate(2L, 20L, 200L, new BigDecimal("0.8"));

        given(entityManager.getReference(eq(DocumentChunk.class), any())).willReturn(null);
        given(entityManager.getReference(eq(Embedding.class), any())).willReturn(null);
        given(searchResultRepository.saveAll(any())).willAnswer(i -> i.getArgument(0));

        List<SearchResult> returned = searchResultCommandService.saveAll(query, List.of(c1, c2));

        ArgumentCaptor<List<SearchResult>> captor = ArgumentCaptor.forClass(List.class);
        then(searchResultRepository).should(times(1)).saveAll(captor.capture());

        List<SearchResult> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getRankNo()).isEqualTo(1);
        assertThat(saved.get(1).getRankNo()).isEqualTo(2);
        assertThat(saved.get(0).getSimilarityScore()).isEqualByComparingTo(new BigDecimal("0.9"));
        assertThat(returned).isEqualTo(saved);
    }

    @Test
    @DisplayName("후보가 없으면 saveAll에 빈 목록을 전달한다")
    void saveAll_emptyCandidates_savesEmptyList() {
        SearchQuery query = SearchQueryFixture.createProcessing();
        given(searchResultRepository.saveAll(any())).willAnswer(i -> i.getArgument(0));

        searchResultCommandService.saveAll(query, List.of());

        ArgumentCaptor<List<SearchResult>> captor = ArgumentCaptor.forClass(List.class);
        then(searchResultRepository).should(times(1)).saveAll(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    private VectorSearchCandidate candidate(Long embeddingId, Long chunkId, Long documentId, BigDecimal score) {
        return new VectorSearchCandidate(embeddingId, chunkId, documentId, "텍스트", 1, "제목", score);
    }
}
