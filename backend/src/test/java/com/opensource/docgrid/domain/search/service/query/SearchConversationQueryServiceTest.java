package com.opensource.docgrid.domain.search.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.search.dto.ConversationContext;
import com.opensource.docgrid.domain.search.repository.SearchConversationRepository;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;

/**
 * 대화 문맥 조회 순서와 벡터 검색용 후속 질문 확장 범위를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchConversationQueryService 단위 테스트")
class SearchConversationQueryServiceTest {

    @InjectMocks
    private SearchConversationQueryService searchConversationQueryService;

    @Mock private SearchConversationRepository searchConversationRepository;
    @Mock private SearchQueryRepository searchQueryRepository;
    @Mock private RagResponseRepository ragResponseRepository;
    @Mock private SearchAnswerQueryService searchAnswerQueryService;

    @Test
    @DisplayName("저장소의 최신순 문맥을 시간순으로 뒤집어 반환한다")
    void findRecentContext_reversesNewestFirstRepositoryResult() {
        ConversationContext older = new ConversationContext("첫 질문", "첫 답변");
        ConversationContext newer = new ConversationContext("둘째 질문", "둘째 답변");
        given(ragResponseRepository.findRecentConversationContext(
            org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(30L),
            org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(List.of(newer, older));

        List<ConversationContext> result = searchConversationQueryService.findRecentContext(10L, 30L, 3);

        assertThat(result).containsExactly(older, newer);
    }

    @Test
    @DisplayName("벡터 검색 문장은 최근 질문 두 개와 현재 질문만 포함한다")
    void contextualizeRetrieval_usesOnlyTwoMostRecentQuestions() {
        List<ConversationContext> context = List.of(
            new ConversationContext("제외될 질문", "답변"),
            new ConversationContext("직전 두 번째 질문", "답변"),
            new ConversationContext("직전 질문", "답변")
        );

        String result = searchConversationQueryService.contextualizeRetrieval("그중 신청 방법은?", context);

        assertThat(result)
            .isEqualTo("이전 질문: 직전 두 번째 질문 / 직전 질문\n현재 질문: 그중 신청 방법은?")
            .doesNotContain("제외될 질문");
    }
}
