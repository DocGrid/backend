package com.opensource.docgrid.domain.search.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.search.entity.SearchConversation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 대화 기록 토글에 표시하는 사용자 소유 대화방 요약.
 *
 * <p>질문·답변 본문은 포함하지 않으며 대화 선택에 필요한 식별자, 제목, 최근 시각만 제공한다.
 */
public record SearchConversationSummaryResponse(
    @Schema(description = "대화방 ID") Long conversationId,
    @Schema(description = "첫 질문에서 만든 대화 제목") String title,
    @Schema(description = "최근 질문 시각") LocalDateTime lastMessageAt
) {
    public static SearchConversationSummaryResponse from(SearchConversation conversation) {
        return new SearchConversationSummaryResponse(
            conversation.getId(), conversation.getTitle(), conversation.getLastMessageAt()
        );
    }
}
