package com.opensource.docgrid.domain.search.dto.response;

import java.util.List;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record SearchResponse(
    @Schema(description = "질문이 속한 대화방 ID") Long conversationId,
    @Schema(description = "검색 요청 ID (search_queries.id)") Long queryId,
    @Schema(description = "검색 결과 목록 (유사도 내림차순)") List<SearchResultItem> results,
    @Schema(description = "RAG 답변 생성 상태 — PROCESSING이면 answer가 아직 null이라는 뜻이며, "
        + "GET /search/{queryId}로 재조회하거나 WebSocket(/user/queue/rag-answer) 알림을 기다려야 한다.")
    ResultStatus ragStatus,
    @Schema(description = "RAG로 생성된 답변, 아직 생성 전이면 null") String answer,
    @Schema(description = "답변의 근거 출처 목록") List<CitationResponse> citations
) {
    /**
     * 벡터 검색 + live check까지 끝난 직후의 응답을 만든다.
     *
     * <p>candidates는 이미 pgvector {@code ORDER BY}로 유사도 내림차순 정렬된 상태이므로,
     * 리스트 인덱스(0-based)에 1을 더한 값을 그대로 rank(1-based)로 사용한다.
     * 검색은 끝났지만 RAG 답변은 아직 시작 전인 시점이라 ragStatus는 항상 PROCESSING,
     * answer/citations는 비워둔다 — 이후 {@link #withAnswer}로 덧씌워진다.
     */
    public static SearchResponse of(Long conversationId, Long queryId, List<VectorSearchCandidate> candidates) {
        List<SearchResultItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            items.add(SearchResultItem.of(i + 1, candidates.get(i)));
        }
        return new SearchResponse(conversationId, queryId, List.copyOf(items), ResultStatus.PROCESSING, null, List.of());
    }

    /**
     * 사용자가 읽을 수 있는 문서가 하나도 없어 벡터 검색 자체를 생략한 경우의 응답이다.
     *
     * <p>{@link #of}와 달리 ragStatus를 처음부터 SUCCESS로 확정한다 — 검색 대상이 없으니
     * RAG도 애초에 태우지 않을 것이라 나중에 채워질 값이 없기 때문이다. 에러가 아닌
     * 정상적인 빈 결과(200)로 취급된다.
     */
    public static SearchResponse empty(Long conversationId, Long queryId) {
        return new SearchResponse(conversationId, queryId, List.of(), ResultStatus.SUCCESS, null, List.of());
    }

    /**
     * RAG 답변이 만들어진 뒤, 기존 검색 결과({@code queryId}/{@code results})는 그대로 두고
     * ragStatus/answer/citations만 새 값으로 교체한 새 응답을 만든다.
     *
     * <p>record는 불변이라 필드를 직접 바꿀 수 없으므로 "새 객체로 대체"하는 방식을 쓴다.
     * {@code SearchController}가 {@code RagFacade.enqueue()} 결과가 즉시 나왔을 때
     * (PROCESSING 상태로 대기하지 않아도 될 때) 이 메서드로 {@link #of}의 결과를 덧씌운다.
     */
    public SearchResponse withAnswer(ResultStatus ragStatus, String answer, List<CitationResponse> citations) {
        return new SearchResponse(conversationId, queryId, results, ragStatus, answer, citations);
    }

    /** 기존 내부 호출이 새 대화방 도입 전 생성자 형태를 계속 사용할 수 있게 한다. */
    public SearchResponse(
        Long queryId,
        List<SearchResultItem> results,
        ResultStatus ragStatus,
        String answer,
        List<CitationResponse> citations
    ) {
        this(null, queryId, results, ragStatus, answer, citations);
    }

    /** 기존 테스트·MCP 호출이 대화방을 직접 지정하지 않는 빈 응답을 만들 때 사용한다. */
    public static SearchResponse empty(Long queryId) {
        return empty(null, queryId);
    }

    /** 기존 테스트가 대화방을 직접 지정하지 않는 처리 중 응답을 만들 때 사용한다. */
    public static SearchResponse of(Long queryId, List<VectorSearchCandidate> candidates) {
        return of(null, queryId, candidates);
    }
}
