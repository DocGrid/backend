package com.opensource.docgrid.domain.search.dto;

/**
 * 후속 질문 해석에 필요한 최근 사용자 질문과 확정된 AI 답변 한 쌍.
 *
 * <p>검색 Vector와 RAG 프롬프트 조립에만 사용하는 내부 DTO이며 API 응답으로 노출하지 않는다.
 */
public record ConversationContext(
    String queryText,
    String answerText
) {
}
