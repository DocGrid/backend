package com.opensource.docgrid.domain.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.dto.RagAnswer;
import com.opensource.docgrid.domain.rag.dto.RagEnqueueOutcome;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.service.command.RagResponseCommandService;
import com.opensource.docgrid.domain.rag.service.command.ResponseCitationCommandService;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import jakarta.persistence.EntityManager;

/**
 * #218(비동기 Job 큐 전환) 이후 RagFacade는 enqueue()(검색 직후 동기, 프롬프트 조립만)와
 * processJob()(RagJobWorker가 비동기로 호출, 실제 LLM 생성+영속화)로 나뉜다.
 */
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
    private RagResponseRepository ragResponseRepository;

    @Mock
    private SearchResultRepository searchResultRepository;

    @Mock
    private EntityManager entityManager;

    private static final Long QUERY_ID = 100L;
    private static final Long JOB_ID = 999L;

    // === enqueue() ===

    @Test
    @DisplayName("enqueue: NO_CONTEXT면 프롬프트 조립·PROCESSING 저장 없이 고정 응답으로 즉시 끝난다")
    void enqueue_noQualifiedCandidates_returnsDoneWithFixedAnswer() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(entityManager.getReference(SearchQuery.class, QUERY_ID)).willReturn(queryRef);
        RagResponse noContextResponse = RagResponse.builder()
            .answerText("관련 문서를 찾지 못했습니다.")
            .status(ResultStatus.SUCCESS)
            .build();
        given(ragResponseCommandService.createNoContext(queryRef)).willReturn(noContextResponse);

        RagEnqueueOutcome outcome = ragFacade.enqueue(QUERY_ID, "질문", List.of());

        assertThat(outcome.pending()).isFalse();
        assertThat(outcome.immediateAnswer().answerText()).isEqualTo("관련 문서를 찾지 못했습니다.");
        assertThat(outcome.immediateAnswer().citations()).isEmpty();
        then(promptBuilder).should(never()).build(anyString(), any());
        then(ragResponseCommandService).should(times(1)).createNoContext(queryRef);
        then(ragResponseCommandService).should(never()).createPending(any(), anyString());
    }

    @Test
    @DisplayName("enqueue: 검색 후보가 있으면 프롬프트만 조립해 PROCESSING으로 저장하고 pending을 반환한다(LLM 호출 없음)")
    void enqueue_withCandidates_savesPendingWithoutCallingOllama() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(entityManager.getReference(SearchQuery.class, QUERY_ID)).willReturn(queryRef);

        VectorSearchCandidate candidate = new VectorSearchCandidate(
            1L, 10L, 100L, "청크 내용", 12, "인사규정", new BigDecimal("0.9")
        );
        List<VectorSearchCandidate> candidates = List.of(candidate);
        given(promptBuilder.build(eq("연차 규정 알려줘"), eq(candidates))).willReturn("조립된 프롬프트");
        given(ragResponseCommandService.createPending(queryRef, "조립된 프롬프트"))
            .willReturn(RagResponse.builder().status(ResultStatus.PROCESSING).build());

        RagEnqueueOutcome outcome = ragFacade.enqueue(QUERY_ID, "연차 규정 알려줘", candidates);

        assertThat(outcome.pending()).isTrue();
        assertThat(outcome.immediateAnswer()).isNull();
        then(ollamaClient).should(never()).generate(anyString());
        then(ragResponseCommandService).should(times(1)).createPending(queryRef, "조립된 프롬프트");
    }

    @Test
    @DisplayName("enqueue: 검색 후보가 3개를 넘으면 LLM 프롬프트에는 상위 3개만 전달한다")
    void enqueue_moreThanMaxPromptCandidates_truncatesForPrompt() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(entityManager.getReference(SearchQuery.class, QUERY_ID)).willReturn(queryRef);

        List<VectorSearchCandidate> candidates = List.of(
            new VectorSearchCandidate(1L, 10L, 100L, "청크1", 1, "문서1", new BigDecimal("0.9")),
            new VectorSearchCandidate(2L, 20L, 200L, "청크2", 2, "문서2", new BigDecimal("0.8")),
            new VectorSearchCandidate(3L, 30L, 300L, "청크3", 3, "문서3", new BigDecimal("0.7")),
            new VectorSearchCandidate(4L, 40L, 400L, "청크4", 4, "문서4", new BigDecimal("0.6")),
            new VectorSearchCandidate(5L, 50L, 500L, "청크5", 5, "문서5", new BigDecimal("0.5"))
        );
        List<VectorSearchCandidate> expectedPromptCandidates = candidates.subList(0, 3);
        given(promptBuilder.build(anyString(), eq(expectedPromptCandidates))).willReturn("조립된 프롬프트");
        given(ragResponseCommandService.createPending(queryRef, "조립된 프롬프트"))
            .willReturn(RagResponse.builder().status(ResultStatus.PROCESSING).build());

        ragFacade.enqueue(QUERY_ID, "질문", candidates);

        then(promptBuilder).should(times(1)).build(anyString(), eq(expectedPromptCandidates));
    }

    // === processJob() ===

    @Test
    @DisplayName("processJob 정상 흐름: Ollama 호출 성공 시 completeSuccess와 citation을 저장한다")
    void processJob_success_savesResponseAndCitations() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));

        OllamaGenerateResult ollamaResult = new OllamaGenerateResult("qwen2.5:7b", "연차는 15일입니다.", 100, 20, 900);
        given(ollamaClient.generate("조립된 프롬프트")).willReturn(ollamaResult);

        SearchResult searchResult = deepStubSearchResult(100L, 10L, "청크 내용", 12, "인사규정", new BigDecimal("0.9"));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of(searchResult));

        ragFacade.processJob(JOB_ID);

        then(ragResponseCommandService).should(times(1)).completeSuccess(eq(job), any());
        then(responseCitationCommandService).should(times(1)).saveAll(eq(job), any(), eq(List.of(searchResult)));
        then(ragResponseCommandService).should(never()).completeFailed(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("processJob Ollama 실패: completeFailed로 최상위 후보 원문을 인용한 extractive fallback을 저장한다")
    void processJob_ollamaFails_savesFailedWithExtractiveFallback() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));

        given(ollamaClient.generate("조립된 프롬프트"))
            .willThrow(new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE));

        SearchResult searchResult = deepStubSearchResult(100L, 10L, "청크 내용", 12, "인사규정", new BigDecimal("0.9"));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of(searchResult));

        ragFacade.processJob(JOB_ID);

        then(ragResponseCommandService).should(times(1)).completeFailed(
            eq(job), argThatFallbackContains("AI 답변 생성이 지연", "청크 내용", "인사규정"), anyString()
        );
        then(responseCitationCommandService).should(never()).saveAll(any(), any(), any());
    }

    @Test
    @DisplayName("processJob LLM 무관 판단: 답변이 안내 문구로 시작하면 citation을 저장하지 않는다")
    void processJob_llmJudgesIrrelevant_skipsCitations() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));

        OllamaGenerateResult ollamaResult =
            new OllamaGenerateResult("qwen2.5:7b", "관련 문서를 찾지 못했습니다.", 100, 10, 500);
        given(ollamaClient.generate("조립된 프롬프트")).willReturn(ollamaResult);
        SearchResult searchResult = deepStubSearchResult(100L, 10L, "청크 내용", 12, "인사규정", new BigDecimal("0.9"));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of(searchResult));

        ragFacade.processJob(JOB_ID);

        then(ragResponseCommandService).should(times(1)).completeSuccess(eq(job), any());
        then(responseCitationCommandService).should(never()).saveAll(any(), any(), any());
    }

    @Test
    @DisplayName("processJob 무관 문구 혼입: 정상 답변 중간에 안내 문구가 섞이면 그 지점부터 제거한 뒤 저장하고 citation은 유지한다")
    void processJob_phraseEmbeddedInAnswer_trimsBeforePersistingAndKeepsCitations() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));

        String answerWithEcho = "pwd는 현재 디렉토리를 출력합니다. "
            + "관련 문서를 찾지 못했습니다. 질문 주제와 관련된 문서가 없습니다.";
        OllamaGenerateResult ollamaResult = new OllamaGenerateResult("qwen2.5:7b", answerWithEcho, 100, 50, 500);
        given(ollamaClient.generate("조립된 프롬프트")).willReturn(ollamaResult);
        SearchResult searchResult = deepStubSearchResult(100L, 10L, "청크 내용", 12, "디렉토리 명령어", new BigDecimal("0.9"));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of(searchResult));

        ragFacade.processJob(JOB_ID);

        then(ragResponseCommandService).should(times(1)).completeSuccess(eq(job),
            org.mockito.ArgumentMatchers.<OllamaGenerateResult>argThat(result ->
                result.answerText().equals("pwd는 현재 디렉토리를 출력합니다.")
            ));
        then(responseCitationCommandService).should(times(1)).saveAll(eq(job), any(), eq(List.of(searchResult)));
    }

    private SearchResult deepStubSearchResult(
        Long documentId, Long chunkId, String chunkText, Integer pageNo, String documentTitle, BigDecimal similarityScore
    ) {
        SearchResult searchResult = mock(SearchResult.class, RETURNS_DEEP_STUBS);
        given(searchResult.getSimilarityScore()).willReturn(similarityScore);
        given(searchResult.getEmbedding()).willReturn(null);
        given(searchResult.getChunk().getId()).willReturn(chunkId);
        given(searchResult.getChunk().getChunkText()).willReturn(chunkText);
        given(searchResult.getChunk().getPageNo()).willReturn(pageNo);
        given(searchResult.getChunk().getDocumentVersion().getDocument().getId()).willReturn(documentId);
        given(searchResult.getChunk().getDocumentVersion().getDocument().getTitle()).willReturn(documentTitle);
        return searchResult;
    }

    private String argThatFallbackContains(String... fragments) {
        // Mockito의 argThat과 조합해 여러 부분 문자열을 한 번에 검증하기 위한 헬퍼.
        return org.mockito.ArgumentMatchers.<String>argThat(text -> {
            for (String fragment : fragments) {
                if (!text.contains(fragment)) return false;
            }
            return true;
        });
    }
}
