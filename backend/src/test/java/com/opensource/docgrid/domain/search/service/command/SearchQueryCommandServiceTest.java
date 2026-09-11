package com.opensource.docgrid.domain.search.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.search.dto.SearchAdmission;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchConversation;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;
import com.opensource.docgrid.domain.search.fixture.SearchQueryFixture;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchQueryCommandService 단위 테스트")
class SearchQueryCommandServiceTest {

    @InjectMocks
    private SearchQueryCommandService searchQueryCommandService;

    @Mock
    private SearchQueryRepository searchQueryRepository;

    @Mock
    private SearchConversationCommandService searchConversationCommandService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CollectionRepository collectionRepository;

    @Test
    @DisplayName("createProcessing: PROCESSING 상태와 VECTOR 타입으로 SearchQuery를 저장한다")
    void createProcessing_savesWithProcessingStatus() {
        User user = User.builder()
            .email("search@test.com")
            .passwordHash("hash")
            .name("검색 사용자")
            .build();
        SearchConversation conversation = mock(SearchConversation.class);
        given(conversation.getId()).willReturn(2L);
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(searchConversationCommandService.resolve(user, null, SearchQueryFixture.QUERY_TEXT))
            .willReturn(conversation);
        given(searchQueryRepository.save(any(SearchQuery.class))).willAnswer(i -> i.getArgument(0));

        SearchAdmission admission = searchQueryCommandService.createProcessing(
            1L, null, null, SearchQueryFixture.QUERY_TEXT, SearchQueryFixture.TOP_K
        );

        ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
        then(searchQueryRepository).should(times(1)).save(captor.capture());

        SearchQuery saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ResultStatus.PROCESSING);
        assertThat(saved.getSearchType()).isEqualTo(SearchType.VECTOR);
        assertThat(saved.getTopK()).isEqualTo(SearchQueryFixture.TOP_K);
        assertThat(saved.getQueryText()).isEqualTo(SearchQueryFixture.QUERY_TEXT);
        assertThat(saved.getQueryEmbeddingModel()).isNull();
        assertThat(saved.getQueryVector()).isNull();
        assertThat(admission.conversationId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("markFailed: PROCESSING 원장을 조건부 UPDATE하면 true를 반환한다")
    void markFailed_returnsTrue_whenProcessingQueryUpdated() {
        given(searchQueryRepository.markFailedIfProcessing(1L, "임베딩 서버 연결 실패"))
            .willReturn(1);

        boolean updated = searchQueryCommandService.markFailed(1L, "임베딩 서버 연결 실패");

        assertThat(updated).isTrue();
        then(searchQueryRepository).should(times(1))
            .markFailedIfProcessing(1L, "임베딩 서버 연결 실패");
    }

    @Test
    @DisplayName("markFailed: 이미 끝난 원장이면 false를 반환한다")
    void markFailed_returnsFalse_whenQueryAlreadyCompleted() {
        given(searchQueryRepository.markFailedIfProcessing(1L, "늦은 실패"))
            .willReturn(0);

        assertThat(searchQueryCommandService.markFailed(1L, "늦은 실패")).isFalse();
    }
}
