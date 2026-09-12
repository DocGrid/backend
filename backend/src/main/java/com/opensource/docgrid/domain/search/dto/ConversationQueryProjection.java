package com.opensource.docgrid.domain.search.dto;

import java.time.LocalDateTime;

/**
 * 대화 상세 화면의 Turn을 만들 때 필요한 검색 질문 필드만 읽는 조회 전용 DTO다.
 */
public record ConversationQueryProjection(
    Long queryId,
    String queryText,
    LocalDateTime createdAt
) {
}
