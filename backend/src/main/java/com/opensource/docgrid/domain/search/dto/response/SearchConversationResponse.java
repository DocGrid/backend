package com.opensource.docgrid.domain.search.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 선택한 대화방의 최근 질문·답변을 시간순으로 반환하는 상세 응답.
 *
 * <p>한 대화가 무한히 커지는 것을 막기 위해 조회 서비스가 최근 50개 Turn으로 제한한다.
 */
public record SearchConversationResponse(
    @Schema(description = "대화방 ID") Long conversationId,
    @Schema(description = "대화 제목") String title,
    @Schema(description = "시간순 질문·답변 목록") List<Turn> turns
) {
    /**
     * 사용자 질문과 해당 검색·RAG 응답을 결합한 대화 한 Turn.
     */
    public record Turn(
        @Schema(description = "검색 요청 ID") Long queryId,
        @Schema(description = "사용자가 입력한 질문") String queryText,
        @Schema(description = "질문 생성 시각") LocalDateTime createdAt,
        @Schema(description = "검색 결과와 AI 답변") SearchResponse response
    ) {
    }
}
