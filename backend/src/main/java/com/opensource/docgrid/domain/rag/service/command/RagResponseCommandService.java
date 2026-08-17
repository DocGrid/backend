package com.opensource.docgrid.domain.rag.service.command;

import org.springframework.stereotype.Service;
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
 * <p>비동기 Job 큐 전환(#218) 이후에는 검색 직후 PROCESSING row를 먼저 저장해두고(createPending),
 * Worker가 LLM 생성을 마친 뒤 markSuccess/markFailed로 같은 row를 채운다 — SearchQuery의
 * PROCESSING 선저장 패턴과 동일해졌다.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class RagResponseCommandService {

    private static final String LLM_PROVIDER = "Ollama";
    // 검색 결과가 0건이라 LLM을 호출하지 않은 경우의 고정 응답 문구
    private static final String NO_CONTEXT_ANSWER_TEXT = "관련 문서를 찾지 못했습니다.";

    private final RagResponseRepository ragResponseRepository;

    // 프롬프트 조립까지는 검색 직후 동기로 끝내고, PROCESSING 상태로 Job 큐에 올린다.
    public RagResponse createPending(SearchQuery query, String promptText) {
        RagResponse ragResponse = RagResponse.builder()
            .query(query)
            .llmProvider(LLM_PROVIDER)
            .promptText(promptText)
            .status(ResultStatus.PROCESSING)
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

    // dirty checking으로 갱신 — pending 상태로 이미 저장된 row를 채우는 것이라 save() 불필요.
    public void completeSuccess(RagResponse ragResponse, OllamaGenerateResult result) {
        ragResponse.markSuccess(
            result.answerText(), result.model(), result.inputTokenCount(), result.outputTokenCount(),
            result.latencyMs()
        );
    }

    public void completeFailed(RagResponse ragResponse, String fallbackAnswerText, String errorMessage) {
        ragResponse.markFailed(fallbackAnswerText, errorMessage);
    }
}
