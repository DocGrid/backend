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
 * Worker가 LLM 생성을 마친 뒤 completeSuccess/completeFailed로 같은 row를 채운다(내부적으로
 * RagResponse 엔티티의 markSuccess/markFailed를 호출) — SearchQuery의 PROCESSING 선저장 패턴과
 * 동일해졌다.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class RagResponseCommandService {

    private static final String LLM_PROVIDER = "Ollama";
    private static final String NO_CONTEXT_ANSWER_TEXT = "관련 문서를 찾지 못했습니다.";

    private final RagResponseRepository ragResponseRepository;

    /**
     * 검색 직후, 프롬프트 조립까지만 동기로 끝내고 PROCESSING 상태로 Job 큐에 올린다.
     * 아직 답이 없으므로 answerText/llmModelName은 채우지 않는다 — 실제 LLM 호출은 나중에
     * RagJobWorker가 한다.
     */
    public RagResponse createPending(SearchQuery query, String promptText) {
        RagResponse ragResponse = RagResponse.builder()
            .query(query)
            .llmProvider(LLM_PROVIDER)
            .promptText(promptText)
            .status(ResultStatus.PROCESSING)
            .build();
        return ragResponseRepository.save(ragResponse);
    }

    /**
     * 검색 결과가 0건(NO_CONTEXT)이라 LLM 호출 자체를 생략한 경우. PROCESSING을 거치지 않고
     * 곧바로 SUCCESS로 확정한다 — 실패가 아니라 "검색은 됐지만 근거가 없다"는 정상 결과이기
     * 때문이다. citation 없이 고정 문구만 기록한다.
     */
    public RagResponse createNoContext(SearchQuery query) {
        RagResponse ragResponse = RagResponse.builder()
            .query(query)
            .answerText(NO_CONTEXT_ANSWER_TEXT)
            .status(ResultStatus.SUCCESS)
            .build();
        return ragResponseRepository.save(ragResponse);
    }

    /**
     * Worker가 LLM 생성에 성공했을 때, createPending()으로 미리 저장해둔 row를 SUCCESS로
     * 채운다. ragResponse는 이미 영속 상태라 save()를 다시 부르지 않아도 트랜잭션 커밋 시점에
     * 더티체킹으로 자동 반영된다.
     */
    public void completeSuccess(RagResponse ragResponse, OllamaGenerateResult result) {
        ragResponse.markSuccess(
            result.answerText(), result.model(), result.inputTokenCount(), result.outputTokenCount(),
            result.latencyMs()
        );
    }

    /**
     * Worker가 LLM 생성에 실패했을 때, 빈손 대신 fallbackAnswerText(대개 extractive fallback)를
     * 채우고 status만 FAILED로 남긴다.
     *
     * <p>검색 도메인의 SearchQueryCommandService.markFailed()와 달리 REQUIRES_NEW가 없다 —
     * 이 메서드를 부르는 RagFacade.processJob()의 catch 블록은 예외를 다시 던지지 않고 그대로
     * return하므로, 이 메서드가 실행되는 트랜잭션 자체가 롤백될 일이 없다. 재전파해서 바깥
     * 트랜잭션을 일부러 굴리는 검색 쪽 구조와 달리, 애초에 롤백될 트랜잭션이 없어 REQUIRES_NEW로
     * 실패 기록을 따로 지킬 필요 자체가 없다.
     */
    public void completeFailed(RagResponse ragResponse, String fallbackAnswerText, String errorMessage) {
        ragResponse.markFailed(fallbackAnswerText, errorMessage);
    }
}
