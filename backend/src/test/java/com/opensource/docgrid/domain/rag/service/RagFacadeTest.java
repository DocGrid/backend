package com.opensource.docgrid.domain.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.dto.RagAnswer;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.service.command.RagResponseCommandService;
import com.opensource.docgrid.domain.rag.service.command.ResponseCitationCommandService;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RagFacade 단위 테스트")
class RagFacadeTest {

    @InjectMocks
    private RagFacade ragFacade;

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private OllamaClient ollamaClient;

    @Mock
    private RagResponseCommandService ragResponseCommandService;

    @Mock
    private ResponseCitationCommandService responseCitationCommandService;

    @Mock
    private EntityManager entityManager;

    private static final Long QUERY_ID = 100L;

    @Test
    @DisplayName("NO_CONTEXT: 검색 후보가 모두 제거되면 LLM과 citation 저장 없이 고정 응답을 저장한다")
    void generate_noQualifiedCandidates_skipsLlmAndCitationAndSavesFixedAnswer() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(entityManager.getReference(SearchQuery.class, QUERY_ID)).willReturn(queryRef);
        RagResponse noContextResponse = RagResponse.builder()
            .answerText("관련 문서를 찾지 못했습니다.")
            .status(ResultStatus.SUCCESS)
            .build();
        given(ragResponseCommandService.createNoContext(queryRef)).willReturn(noContextResponse);

        RagAnswer answer = ragFacade.generate(QUERY_ID, "질문", List.of(), List.of());

        assertThat(answer.answerText()).isEqualTo("관련 문서를 찾지 못했습니다.");
        assertThat(answer.citations()).isEmpty();
        then(promptBuilder).should(never()).build(anyString(), any());
        then(ollamaClient).should(never()).generate(anyString());
        then(ragResponseCommandService).should(times(1)).createNoContext(queryRef);
        then(ragResponseCommandService).should(never()).createSuccess(any(), anyString(), any());
        then(ragResponseCommandService).should(never()).createFailed(any(), anyString(), anyString());
        then(responseCitationCommandService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("정상 흐름: 프롬프트 조립 후 Ollama 호출, rag_responses/citations 저장, answer+citations를 반환한다")
    void generate_success_savesResponseAndCitations() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(entityManager.getReference(SearchQuery.class, QUERY_ID)).willReturn(queryRef);

        VectorSearchCandidate candidate = new VectorSearchCandidate(
            1L, 10L, 100L, "청크 내용", 12, "인사규정", new BigDecimal("0.9")
        );
        List<VectorSearchCandidate> candidates = List.of(candidate);
        SearchResult searchResult = mock(SearchResult.class);
        List<SearchResult> searchResults = List.of(searchResult);

        given(promptBuilder.build(eq("연차 규정 알려줘"), eq(candidates))).willReturn("조립된 프롬프트");
        OllamaGenerateResult ollamaResult = new OllamaGenerateResult("qwen2.5:3b", "연차는 15일입니다.", 100, 20, 900);
        given(ollamaClient.generate("조립된 프롬프트")).willReturn(ollamaResult);
        RagResponse ragResponse = RagResponse.builder()
            .answerText("연차는 15일입니다.")
            .status(ResultStatus.SUCCESS)
            .build();
        given(ragResponseCommandService.createSuccess(queryRef, "조립된 프롬프트", ollamaResult)).willReturn(ragResponse);

        RagAnswer answer = ragFacade.generate(QUERY_ID, "연차 규정 알려줘", candidates, searchResults);

        assertThat(answer.answerText()).isEqualTo("연차는 15일입니다.");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.citations().get(0).label()).isEqualTo("[1]");
        assertThat(answer.citations().get(0).documentId()).isEqualTo(100L);
        then(responseCitationCommandService).should(times(1)).saveAll(ragResponse, candidates, searchResults);
        then(ragResponseCommandService).should(never()).createFailed(any(), any(), any());
    }

    @Test
    @DisplayName("Ollama 호출 실패: FAILED로 기록하고 검색 후보가 포함된 저하 응답을 반환한다")
    void generate_ollamaFails_savesFailedAndReturnsFallback() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(entityManager.getReference(SearchQuery.class, QUERY_ID)).willReturn(queryRef);

        VectorSearchCandidate candidate = new VectorSearchCandidate(
            1L, 10L, 100L, "청크 내용", 12, "인사규정", new BigDecimal("0.9")
        );
        List<VectorSearchCandidate> candidates = List.of(candidate);

        given(promptBuilder.build(anyString(), eq(candidates))).willReturn("조립된 프롬프트");
        given(ollamaClient.generate("조립된 프롬프트"))
            .willThrow(new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE));

        RagAnswer answer = ragFacade.generate(QUERY_ID, "질문", candidates, List.of());

        assertThat(answer.answerText()).contains("AI 답변 생성이 지연");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.citations().get(0).documentId()).isEqualTo(100L);

        then(ragResponseCommandService).should(times(1))
            .createFailed(eq(queryRef), eq("조립된 프롬프트"), anyString());
        then(responseCitationCommandService).should(never()).saveAll(any(), any(), any());
    }
}
