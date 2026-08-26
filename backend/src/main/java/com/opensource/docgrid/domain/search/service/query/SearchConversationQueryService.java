package com.opensource.docgrid.domain.search.service.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.search.dto.ConversationContext;
import com.opensource.docgrid.domain.search.dto.response.SearchConversationResponse;
import com.opensource.docgrid.domain.search.dto.response.SearchConversationSummaryResponse;
import com.opensource.docgrid.domain.search.entity.SearchConversation;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.repository.SearchConversationRepository;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 사용자 대화방 목록·상세와 후속 질문용 제한된 최근 문맥을 조회한다.
 *
 * <p>API 상세는 최근 50개 Turn, 내부 문맥은 호출자가 요청한 소수 Turn으로 제한하며
 * 다른 사용자의 대화방은 항상 찾을 수 없는 것으로 처리한다.
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class SearchConversationQueryService {

    private static final int RETRIEVAL_CONTEXT_TURNS = 2;

    private final SearchConversationRepository searchConversationRepository;
    private final SearchQueryRepository searchQueryRepository;
    private final RagResponseRepository ragResponseRepository;
    private final SearchAnswerQueryService searchAnswerQueryService;

    public PageResponse<SearchConversationSummaryResponse> getConversations(Long userId, int page, int size) {
        Page<SearchConversation> conversations = searchConversationRepository
            .findByUser_IdOrderByLastMessageAtDesc(userId, PageRequest.of(page, size));
        List<SearchConversationSummaryResponse> content = conversations.getContent().stream()
            .map(SearchConversationSummaryResponse::from)
            .toList();
        return PageResponse.from(conversations, content);
    }

    public SearchConversationResponse getConversation(Long conversationId, Long userId) {
        SearchConversation conversation = ownedConversation(conversationId, userId);
        List<SearchQuery> queries = new ArrayList<>(
            searchQueryRepository.findTop50ByConversation_IdOrderByCreatedAtDesc(conversationId)
        );
        Collections.reverse(queries);
        List<SearchConversationResponse.Turn> turns = queries.stream()
            .map(query -> new SearchConversationResponse.Turn(
                query.getId(), query.getQueryText(), query.getCreatedAt(),
                searchAnswerQueryService.getAnswer(query.getId(), userId)
            ))
            .toList();
        return new SearchConversationResponse(conversation.getId(), conversation.getTitle(), turns);
    }

    public List<ConversationContext> findRecentContext(
        Long conversationId,
        Long excludedQueryId,
        int maxTurns
    ) {
        List<ConversationContext> context = new ArrayList<>(ragResponseRepository.findRecentConversationContext(
            conversationId, excludedQueryId, PageRequest.of(0, maxTurns)
        ));
        Collections.reverse(context);
        return List.copyOf(context);
    }

    public String contextualizeRetrieval(String queryText, List<ConversationContext> context) {
        if (context.isEmpty()) return queryText;
        List<ConversationContext> recent = context.size() > RETRIEVAL_CONTEXT_TURNS
            ? context.subList(context.size() - RETRIEVAL_CONTEXT_TURNS, context.size())
            : context;
        String previousQuestions = recent.stream()
            .map(ConversationContext::queryText)
            .reduce((first, second) -> first + " / " + second)
            .orElse("");
        return "이전 질문: %s\n현재 질문: %s".formatted(previousQuestions, queryText);
    }

    private SearchConversation ownedConversation(Long conversationId, Long userId) {
        return searchConversationRepository.findByIdAndUser_Id(conversationId, userId)
            .orElseThrow(() -> new DocGridException(ErrorCode.SEARCH_CONVERSATION_NOT_FOUND));
    }
}
