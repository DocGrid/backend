package com.opensource.docgrid.domain.search.service.command;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.search.entity.SearchConversation;
import com.opensource.docgrid.domain.search.repository.SearchConversationRepository;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 새 검색 대화방 생성과 기존 대화방의 최근 활동 시각 갱신을 담당한다.
 *
 * <p>다른 사용자의 conversationId가 들어오면 존재 여부를 숨긴 404로 차단하며,
 * 질문·답변 본문 저장은 기존 SearchQuery/RagResponse 서비스에 위임한다.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class SearchConversationCommandService {

    private static final int MAX_TITLE_CODE_POINTS = 80;

    private final SearchConversationRepository searchConversationRepository;

    public SearchConversation resolve(User user, Long conversationId, String firstQueryText) {
        LocalDateTime now = LocalDateTime.now();
        if (conversationId == null) {
            return searchConversationRepository.save(SearchConversation.builder()
                .user(user)
                .title(titleFrom(firstQueryText))
                .lastMessageAt(now)
                .build());
        }

        SearchConversation conversation = searchConversationRepository.findByIdAndUser_Id(conversationId, user.getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SEARCH_CONVERSATION_NOT_FOUND));
        conversation.recordMessage(now);
        return conversation;
    }

    private String titleFrom(String queryText) {
        String title = queryText.strip();
        if (title.codePointCount(0, title.length()) <= MAX_TITLE_CODE_POINTS) {
            return title;
        }
        int endIndex = title.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS - 1);
        return title.substring(0, endIndex) + "…";
    }
}
