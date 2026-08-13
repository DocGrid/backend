package com.opensource.docgrid.domain.rag.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.dto.RagAnswer;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.service.command.RagResponseCommandService;
import com.opensource.docgrid.domain.rag.service.command.ResponseCitationCommandService;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.global.exception.DocGridException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RAG 답변 생성 전체 흐름을 조율하는 Facade (F-RAG-05).
 *
 * <pre>
 * 1. candidates가 비어있으면(NO_CONTEXT) LLM 호출 없이 고정 응답 저장
 * 2. PromptBuilder로 프롬프트 조립
 * 3. OllamaClient 호출
 * 4. rag_responses 저장 (성공/실패)
 * 5. 성공 시 response_citations 저장, 실패 시 검색 후보와 안내 답변 반환
 * </pre>
 *
 * <p>SearchFacade와 별도 트랜잭션으로 분리되어 있다(SearchController가 순차 호출) — 검색 DB 작업과
 * LLM HTTP 호출을 포함한 RAG DB 작업이 하나의 커넥션을 오래 물고 있지 않도록 하기 위함이다.
 */
@Transactional
@Service
@RequiredArgsConstructor
@Slf4j
public class RagFacade {

    private static final String LLM_FALLBACK_ANSWER =
        "관련 문서는 찾았지만 AI 답변 생성이 지연되고 있습니다. 아래 검색 결과와 근거 문서를 확인해 주세요.";

    private final PromptBuilder promptBuilder;
    private final OllamaClient ollamaClient;
    private final RagResponseCommandService ragResponseCommandService;
    private final ResponseCitationCommandService responseCitationCommandService;
    private final EntityManager entityManager;

    public RagAnswer generate(
        Long queryId, String queryText, List<VectorSearchCandidate> candidates, List<SearchResult> searchResults
    ) {
        SearchQuery queryRef = entityManager.getReference(SearchQuery.class, queryId);

        // 검색 후보가 없으면(NO_CONTEXT) LLM 호출 없이 고정 응답 저장
        if (candidates.isEmpty()) {
            RagResponse ragResponse = ragResponseCommandService.createNoContext(queryRef);
            log.info("[RAG] no context queryId={} responseId={}", queryId, ragResponse.getId());
            return RagAnswer.noContext(ragResponse.getAnswerText());
        }

        // 검색 후보가 있으면 프롬프트 조립 후 LLM 호출
        String prompt = promptBuilder.build(queryText, candidates);
        OllamaGenerateResult result;
        try {
            result = ollamaClient.generate(prompt);
        } catch (DocGridException e) {
            ragResponseCommandService.createFailed(queryRef, prompt, e.getMessage());
            // LLM 장애가 권한 검증을 통과한 벡터 검색 결과까지 숨기지 않도록 저하 응답으로 마무리한다.
            log.warn("[RAG] fallback queryId={} errorCode={}", queryId, e.getErrorCode().getCode());
            return RagAnswer.of(LLM_FALLBACK_ANSWER, candidates);
        }

        // LLM 이후의 영속화 실패는 검색 저하 응답으로 숨기지 않고 Transaction 오류로 전달한다.
        RagResponse ragResponse = ragResponseCommandService.createSuccess(queryRef, prompt, result);
        responseCitationCommandService.saveAll(ragResponse, candidates, searchResults);
        log.info("[RAG] done queryId={} responseId={} latencyMs={}", queryId, ragResponse.getId(), result.latencyMs());
        return RagAnswer.of(result.answerText(), candidates);
    }
}
