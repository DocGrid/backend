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

    /**
     * 첫 질문이면 새 대화방을 만들고 conversationId가 있으면 사용자 소유 대화의 활동 시각을 갱신한다.
     */
    public SearchConversation resolve(User user, Long conversationId, String firstQueryText) {
        // 1. 한 요청 안의 생성 또는 갱신 시각이 동일하도록 현재 시각을 한 번만 계산한다.
        LocalDateTime now = LocalDateTime.now();

        // 2. 대화 ID가 없으면 첫 질문에서 안전한 길이의 제목을 만들고 새 대화방을 저장한다.
        if (conversationId == null) {
            return searchConversationRepository.save(SearchConversation.builder()
                .user(user)
                .title(titleFrom(firstQueryText))
                .lastMessageAt(now)
                .build());
        }

        // 3. 기존 대화는 사용자 소유권을 함께 확인하고 최근 활동 시각만 갱신한다.
        SearchConversation conversation = searchConversationRepository.findByIdAndUser_Id(conversationId, user.getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SEARCH_CONVERSATION_NOT_FOUND));
        conversation.recordMessage(now);
        return conversation;
    }

    /**
     * 첫 질문을 대화 제목으로 사용하되 Unicode Code Point 경계에서 최대 길이로 줄인다.
     */
    private String titleFrom(String queryText) {
        String title = queryText.strip();
        if (title.codePointCount(0, title.length()) <= MAX_TITLE_CODE_POINTS) {
            return title;
        }
        int endIndex = title.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS - 1);
        return title.substring(0, endIndex) + "…";
    }
}
