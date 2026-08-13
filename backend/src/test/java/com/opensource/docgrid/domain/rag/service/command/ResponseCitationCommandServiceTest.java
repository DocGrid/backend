package com.opensource.docgrid.domain.rag.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
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
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.entity.ResponseCitation;
import com.opensource.docgrid.domain.rag.repository.ResponseCitationRepository;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseCitationCommandService 단위 테스트")
class ResponseCitationCommandServiceTest {

    @InjectMocks
    private ResponseCitationCommandService responseCitationCommandService;

    @Mock
    private ResponseCitationRepository responseCitationRepository;

    @Mock
    private EntityManager entityManager;

    @Test
    @DisplayName("후보 목록을 순서대로 citation_order/citation_label과 함께 저장한다")
    void saveAll_savesWithOrderAndLabel() {
        RagResponse response = RagResponse.builder()
            .answerText("연차는 입사 1년 기준 15일 부여됩니다.")
            .status(ResultStatus.SUCCESS)
            .build();
        VectorSearchCandidate c1 = candidate(10L, "인사규정 내용", 12, new BigDecimal("0.9"));
        VectorSearchCandidate c2 = candidate(20L, "복지정책 내용", 3, new BigDecimal("0.8"));
        DocumentChunk chunk1 = mock(DocumentChunk.class);
        DocumentChunk chunk2 = mock(DocumentChunk.class);
        // searchResult1/2는 SearchFacade 트랜잭션에서 이미 저장되어 detached된 엔티티를 흉내낸다 — id만 의미가 있다.
        SearchResult searchResult1 = mock(SearchResult.class);
        SearchResult searchResult2 = mock(SearchResult.class);
        given(searchResult1.getId()).willReturn(501L);
        given(searchResult2.getId()).willReturn(502L);
        SearchResult searchResultRef1 = mock(SearchResult.class);
        SearchResult searchResultRef2 = mock(SearchResult.class);

        given(entityManager.getReference(DocumentChunk.class, c1.chunkId())).willReturn(chunk1);
        given(entityManager.getReference(DocumentChunk.class, c2.chunkId())).willReturn(chunk2);
        given(entityManager.getReference(SearchResult.class, 501L)).willReturn(searchResultRef1);
        given(entityManager.getReference(SearchResult.class, 502L)).willReturn(searchResultRef2);
        given(responseCitationRepository.saveAll(any())).willAnswer(i -> i.getArgument(0));

        responseCitationCommandService.saveAll(response, List.of(c1, c2), List.of(searchResult1, searchResult2));

        ArgumentCaptor<List<ResponseCitation>> captor = ArgumentCaptor.forClass(List.class);
        then(responseCitationRepository).should(times(1)).saveAll(captor.capture());

        List<ResponseCitation> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getCitationOrder()).isEqualTo(1);
        assertThat(saved.get(0).getCitationLabel()).isEqualTo("[1]");
        assertThat(saved.get(0).getQuotedText()).isEqualTo("인사규정 내용");
        assertThat(saved.get(0).getPageNo()).isEqualTo(12);
        assertThat(saved.get(0).getRelevanceScore()).isEqualByComparingTo(new BigDecimal("0.9"));
        assertThat(saved.get(0).getSearchResult()).isSameAs(searchResultRef1);
        assertThat(saved.get(0).getChunk()).isSameAs(chunk1);
        assertThat(saved.get(1).getCitationOrder()).isEqualTo(2);
        assertThat(saved.get(1).getCitationLabel()).isEqualTo("[2]");
        assertThat(saved.get(1).getChunk()).isSameAs(chunk2);
        assertThat(saved.get(1).getSearchResult()).isSameAs(searchResultRef2);
    }

    @Test
    @DisplayName("후보가 없으면 saveAll에 빈 목록을 전달한다")
    void saveAll_emptyCandidates_savesEmptyList() {
        RagResponse response = RagResponse.builder()
            .answerText("관련 문서를 찾지 못했습니다.")
            .status(ResultStatus.SUCCESS)
            .build();
        given(responseCitationRepository.saveAll(any())).willAnswer(i -> i.getArgument(0));

        responseCitationCommandService.saveAll(response, List.of(), List.of());

        ArgumentCaptor<List<ResponseCitation>> captor = ArgumentCaptor.forClass(List.class);
        then(responseCitationRepository).should(times(1)).saveAll(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    private VectorSearchCandidate candidate(Long chunkId, String chunkText, Integer pageNo, BigDecimal score) {
        return new VectorSearchCandidate(1L, chunkId, 100L, chunkText, pageNo, "제목", score);
    }
}
