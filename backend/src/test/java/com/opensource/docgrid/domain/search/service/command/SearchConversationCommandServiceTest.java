package com.opensource.docgrid.domain.search.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.search.entity.SearchConversation;
import com.opensource.docgrid.domain.search.repository.SearchConversationRepository;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 대화 생성과 소유자 검증이라는 SearchConversationCommandService의 상태 변경 경계를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchConversationCommandService 단위 테스트")
class SearchConversationCommandServiceTest {

    @InjectMocks
    private SearchConversationCommandService searchConversationCommandService;

    @Mock
    private SearchConversationRepository searchConversationRepository;

    @Test
    @DisplayName("새 대화는 첫 질문을 80자로 줄인 제목과 최근 메시지 시각으로 저장한다")
    void resolve_withoutConversationId_createsConversation() {
        User user = User.builder().email("user@test.local").passwordHash("x").name("사용자").build();
        given(searchConversationRepository.save(any(SearchConversation.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        SearchConversation conversation = searchConversationCommandService.resolve(user, null, "가".repeat(100));

        ArgumentCaptor<SearchConversation> captor = ArgumentCaptor.forClass(SearchConversation.class);
        org.mockito.BDDMockito.then(searchConversationRepository).should().save(captor.capture());
        assertThat(conversation).isSameAs(captor.getValue());
        assertThat(conversation.getTitle()).isEqualTo("가".repeat(79) + "…");
        assertThat(conversation.getLastMessageAt()).isNotNull();
    }

    @Test
    @DisplayName("다른 사용자 소유의 대화 ID는 존재 여부를 숨긴 not found로 거절한다")
    void resolve_unownedConversation_throwsNotFound() {
        User user = org.mockito.Mockito.mock(User.class);
        given(user.getId()).willReturn(1L);
        given(searchConversationRepository.findByIdAndUser_Id(99L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> searchConversationCommandService.resolve(user, 99L, "후속 질문"))
            .isInstanceOf(DocGridException.class)
            .extracting(error -> ((DocGridException) error).getErrorCode())
            .isEqualTo(ErrorCode.SEARCH_CONVERSATION_NOT_FOUND);
    }
}
