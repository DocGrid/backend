package com.opensource.docgrid.domain.rag.service.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

import lombok.RequiredArgsConstructor;

/**
 * RAG 최종 답변 저장 서비스 (F-RAG-03).
 *
 * <p>OllamaClient.generate() 호출은 한 번에 성공/실패가 갈리는 단일 작업이라, SearchQuery처럼
 * PROCESSING을 먼저 저장하지 않고 결과가 나온 시점에 SUCCESS/FAILED로 한 번에 저장한다.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class RagResponseCommandService {

    private static final String LLM_PROVIDER = "Ollama";
    // answer_text는 NOT NULL 제약이라 실패 시에도 고정 문구를 저장한다. 실제 사유는 errorMessage에 담긴다.
    private static final String FAILED_ANSWER_TEXT = "답변 생성에 실패했습니다.";
    // 검색 결과가 0건이라 LLM을 호출하지 않은 경우의 고정 응답 문구
    private static final String NO_CONTEXT_ANSWER_TEXT = "관련 문서를 찾지 못했습니다.";

    private final RagResponseRepository ragResponseRepository;

    public RagResponse createSuccess(SearchQuery query, String promptText, OllamaGenerateResult result) {
        RagResponse ragResponse = RagResponse.builder()
            .query(query)
            .answerText(result.answerText())
            .llmProvider(LLM_PROVIDER)
            .llmModelName(result.model())
            .promptText(promptText)
            .inputTokenCount(result.inputTokenCount())
            .outputTokenCount(result.outputTokenCount())
            .latencyMs(result.latencyMs())
            .status(ResultStatus.SUCCESS)
            .build();
        return ragResponseRepository.save(ragResponse);
    }

    // 검색 결과가 0건이라 LLM 호출 자체를 생략한 경우. citation 없이 고정 문구로 SUCCESS 기록한다.
    public RagResponse createNoContext(SearchQuery query) {
        RagResponse ragResponse = RagResponse.builder()
            .query(query)
            .answerText(NO_CONTEXT_ANSWER_TEXT)
            .status(ResultStatus.SUCCESS)
            .build();
        return ragResponseRepository.save(ragResponse);
    }

    // REQUIRES_NEW: 상위 트랜잭션이 롤백돼도 FAILED 기록은 독립 트랜잭션으로 저장된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RagResponse createFailed(SearchQuery query, String promptText, String errorMessage) {
        RagResponse ragResponse = RagResponse.builder()
            .query(query)
            .answerText(FAILED_ANSWER_TEXT)
            .llmProvider(LLM_PROVIDER)
            .promptText(promptText)
            .status(ResultStatus.FAILED)
            .errorMessage(errorMessage)
            .build();
        return ragResponseRepository.save(ragResponse);
    }
}
