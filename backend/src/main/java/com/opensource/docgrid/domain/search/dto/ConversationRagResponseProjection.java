package com.opensource.docgrid.domain.search.dto;

import com.opensource.docgrid.domain.search.enums.ResultStatus;

/**
 * 여러 검색 질문의 RAG 처리 상태와 확정 답변을 queryId별로 조립하기 위한 조회 전용 DTO다.
 */
public record ConversationRagResponseProjection(
    Long queryId,
    ResultStatus status,
    String answerText
) {
}
