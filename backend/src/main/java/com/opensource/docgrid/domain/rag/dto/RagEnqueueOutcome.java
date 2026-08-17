package com.opensource.docgrid.domain.rag.dto;

/**
 * RagFacade.enqueue() 반환 타입 — 검색 후보가 없어(NO_CONTEXT) LLM 호출 없이 즉시 끝난 경우와,
 * PROCESSING으로 Job 큐에 올라가 Worker의 처리를 기다려야 하는 경우를 구분한다.
 */
public record RagEnqueueOutcome(RagAnswer immediateAnswer, boolean pending) {

    public static RagEnqueueOutcome stillPending() {
        return new RagEnqueueOutcome(null, true);
    }

    public static RagEnqueueOutcome done(RagAnswer answer) {
        return new RagEnqueueOutcome(answer, false);
    }
}
