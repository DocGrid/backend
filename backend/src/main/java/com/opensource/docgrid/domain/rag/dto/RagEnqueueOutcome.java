package com.opensource.docgrid.domain.rag.dto;

/**
 * RagFacade.enqueue() 반환 타입 — 검색 후보가 없어(NO_CONTEXT) LLM 호출 없이 즉시 끝난 경우와,
 * PROCESSING으로 Job 큐에 올라가 Worker의 처리를 기다려야 하는 경우를 구분한다.
 *
 * <p>{@code pending}이 이미 "{@code immediateAnswer}를 봐도 되는지"를 말해주는 계약이다 —
 * {@code pending=true}이면 {@code immediateAnswer}는 항상 {@code null}이며 호출 측(SearchController)은
 * 그 값을 들여다보지 않는다. 이 계약 덕분에 {@code Optional<RagAnswer>}로 한 번 더 감쌀 필요가 없다.
 */
public record RagEnqueueOutcome(RagAnswer immediateAnswer, boolean pending) {

    /**
     * 검색 후보가 있어 프롬프트까지는 조립했지만, 아직 Ollama를 호출하지 않고 PROCESSING
     * 상태로 큐에만 올려둔 경우. 실제 LLM 호출은 나중에 RagJobWorker가 한다 — 지금은 답이
     * 없으니 answer는 항상 null이다.
     */
    public static RagEnqueueOutcome stillPending() {
        return new RagEnqueueOutcome(null, true);
    }

    /**
     * 검색 후보가 0건(NO_CONTEXT)이라 Ollama를 호출할 필요조차 없어, 그 자리에서 바로
     * 고정 답변을 만들어 완료한 경우. Worker의 처리를 기다릴 필요가 없다.
     */
    public static RagEnqueueOutcome done(RagAnswer answer) {
        return new RagEnqueueOutcome(answer, false);
    }
}
