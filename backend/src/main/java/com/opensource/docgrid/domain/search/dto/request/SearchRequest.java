package com.opensource.docgrid.domain.search.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 문서 검색과 선택적 대화방 연결을 요청하는 입력 계약.
 *
 * <p>conversationId가 없으면 새 대화를 만들고, 있으면 소유권 확인 후 후속 질문으로 추가한다.
 */
public record SearchRequest(
    @NotBlank String queryText,
    @Min(1) @Max(20) Integer topK,
    @Positive Long collectionId,
    @Positive Long conversationId
) {
    private static final int DEFAULT_TOP_K = 5;

    public int effectiveTopK() {
        return topK != null ? topK : DEFAULT_TOP_K;
    }

    /** 기존 MCP·테스트 호출자가 새 대화 검색을 그대로 사용할 수 있게 하는 호환 생성자. */
    public SearchRequest(String queryText, Integer topK, Long collectionId) {
        this(queryText, topK, collectionId, null);
    }
}
