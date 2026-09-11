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
 * Worker가 LLM 생성을 마친 뒤 completeSuccess/completeFailed로 같은 row를 채운다 — SearchQuery의
 * PROCESSING 선저장 패턴과 동일해졌다. 이 둘은 RagJobTimeoutSweeper와의 경합(#288) 때문에
 * 엔티티 dirty checking이 아니라 {@code RagResponseRepository}의 조건부 UPDATE(WHERE
 * status=PROCESSING)로 직접 확정하고, 실제로 확정이 일어났는지를 boolean으로 반환한다.
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
     * 채운다.
     *
     * <p>엔티티를 불러와 마크하고 dirty checking에 맡기는 대신, {@link
     * RagResponseRepository#completeSuccessIfProcessing}(조건부 UPDATE, {@code WHERE
     * status = PROCESSING})으로 직접 확정한다 — RagJobTimeoutSweeper가 이 job을 먼저 FAILED로
     * 강제 종료했다면, Worker의 이 뒤늦은 성공 처리가 그 결과를 조건 없이 덮어써버리는 경합
     * (#288)을 막기 위함이다. 영향받은 행이 0건이면(=스위퍼가 먼저 확정함) {@code false}를
     * 반환하고, 호출자(RagFacade.processJob)는 이 경우 citation 저장도 건너뛴다 — 이미 아무도
     * 안 볼 결과이기 때문이다.
     *
     * @return 실제로 이 호출로 SUCCESS 확정이 일어났으면 true, 이미 다른 경로(스위퍼)가
     *         먼저 끝내 아무 일도 하지 않았으면 false.
     */
    public boolean completeSuccess(RagResponse ragResponse, OllamaGenerateResult result) {
        int updated = ragResponseRepository.completeSuccessIfProcessing(
            ragResponse.getId(), result.answerText(), result.model(),
            result.inputTokenCount(), result.outputTokenCount(), result.latencyMs()
        );
        return updated > 0;
    }

    /**
     * Worker가 LLM 생성에 실패했을 때, 빈손 대신 fallbackAnswerText(대개 extractive fallback)를
     * 채우고 status만 FAILED로 남긴다.
     *
     * <p>{@link RagResponseRepository#forceFailIfProcessing}(조건부 UPDATE)을
     * RagJobTimeoutSweeper와 공유해서 쓴다 — 이유는 {@link #completeSuccess}와 동일하다.
     *
     * <p>이 메서드를 부르는 RagFacade.processJob()의 catch 블록은 예외를 다시 던지지 않고 그대로
     * return하므로, 현재 Transaction이 롤백될 일이 없다. 반면 검색 도메인은 외부 호출 예외를
     * 재전파하므로 SearchQueryCommandService.markFailed()가 독립 Transaction에서 실패 원장을
     * 확정한다.
     *
     * @return 실제로 이 호출로 FAILED 확정이 일어났으면 true, 이미 다른 경로(스위퍼)가
     *         먼저 끝내 아무 일도 하지 않았으면 false.
     */
    public boolean completeFailed(RagResponse ragResponse, String fallbackAnswerText, String errorMessage) {
        int updated = ragResponseRepository.forceFailIfProcessing(
            ragResponse.getId(), fallbackAnswerText, errorMessage);
        return updated > 0;
    }
}
