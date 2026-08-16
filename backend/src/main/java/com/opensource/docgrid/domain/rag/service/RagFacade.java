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

    private static final String LLM_FALLBACK_PREFIX = "AI 답변 생성이 지연되고 있습니다. "
        + "가장 관련도 높은 문서에서 다음 내용을 찾았습니다:\n\n";

    // fallback 문구에 원문을 통째로 붙이면 답변이 지나치게 길어져, 미리보기 수준으로만 잘라 보여준다.
    private static final int FALLBACK_EXCERPT_MAX_CODE_POINTS = 300;

    // topK는 호출자가 1~20까지 자유롭게 요청할 수 있어(SearchRequest), 후보 수를 그대로 프롬프트에
    // 다 넣으면 prefill 시간이 예측 불가능해져 read-timeout(25s)을 넘기는 경우가 생긴다.
    // 화면에 보여줄 인용 문서 수(topK)와 별개로, LLM이 실제로 읽는 후보 수는 이 값으로 고정한다.
    private static final int MAX_PROMPT_CANDIDATES = 3;

    // PromptBuilder가 LLM에게 무관한 문서일 때 이 문구로만 답하도록 지시한다 — 검색은 됐지만(candidates
    // 존재) LLM이 무관하다고 판단한 경우, 화면에 근거 문서를 같이 보여주면 안내 문구와 모순돼 보인다.
    private static final String NO_RELEVANT_DOC_PHRASE = "관련 문서를 찾지 못했습니다";

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

        // 검색 후보가 있으면 프롬프트 조립 후 LLM 호출 (LLM 입력은 상위 MAX_PROMPT_CANDIDATES개로 제한)
        List<VectorSearchCandidate> promptCandidates = candidates.size() > MAX_PROMPT_CANDIDATES
            ? candidates.subList(0, MAX_PROMPT_CANDIDATES)
            : candidates;
        String prompt = promptBuilder.build(queryText, promptCandidates);
        OllamaGenerateResult result;
        try {
            result = ollamaClient.generate(prompt);
        } catch (DocGridException e) {
            ragResponseCommandService.createFailed(queryRef, prompt, e.getMessage());
            // LLM 장애가 권한 검증을 통과한 벡터 검색 결과까지 숨기지 않도록, 최상위 후보 원문을
            // 그대로 인용해 최소한의 답을 제공한다(extractive fallback).
            log.warn("[RAG] fallback queryId={} errorCode={}", queryId, e.getErrorCode().getCode());
            return RagAnswer.of(buildExtractiveFallbackAnswer(candidates), candidates);
        }

        // LLM 이후의 영속화 실패는 검색 저하 응답으로 숨기지 않고 Transaction 오류로 전달한다.
        RagResponse ragResponse = ragResponseCommandService.createSuccess(queryRef, prompt, result);
        responseCitationCommandService.saveAll(ragResponse, candidates, searchResults);
        log.info("[RAG] done queryId={} responseId={} latencyMs={}", queryId, ragResponse.getId(), result.latencyMs());

        // LLM이 무관하다고 판단해 안내 문구로만 답했으면, 후보 문서를 근거처럼 같이 보여주지 않는다.
        // 단, 7B 모델이 정상 답변을 끝낸 뒤 지시문을 메아리처럼 이 문구를 덧붙이는 패턴이 관찰됨 —
        // 문구가 답변의 사실상 전부(맨 앞)일 때만 무관으로 취급하고, 정상 답변 중간에 박힌 문구는
        // 그 지점부터 잘라내고 근거 문서는 유지한다.
        String answerText = result.answerText();
        int phraseIndex = answerText != null ? answerText.indexOf(NO_RELEVANT_DOC_PHRASE) : -1;
        if (phraseIndex >= 0) {
            if (answerText.strip().startsWith(NO_RELEVANT_DOC_PHRASE)) {
                return RagAnswer.of(answerText, List.of());
            }
            log.warn("[RAG] 정상 답변에 무관 안내 문구 혼입, 해당 지점부터 제거: queryId={} phraseIndex={}",
                queryId, phraseIndex);
            answerText = answerText.substring(0, phraseIndex).strip();
        }
        return RagAnswer.of(answerText, candidates);
    }

    private String buildExtractiveFallbackAnswer(List<VectorSearchCandidate> candidates) {
        VectorSearchCandidate top = candidates.get(0);
        String pageSuffix = top.pageNo() != null ? " " + top.pageNo() + "페이지" : "";
        String excerpt = truncate(top.chunkText(), FALLBACK_EXCERPT_MAX_CODE_POINTS);
        return "%s\"%s\" (%s%s)".formatted(LLM_FALLBACK_PREFIX, excerpt, top.documentTitle(), pageSuffix);
    }

    private String truncate(String text, int maxCodePoints) {
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxCodePoints - 1);
        return text.substring(0, endIndex) + "…";
    }
}
