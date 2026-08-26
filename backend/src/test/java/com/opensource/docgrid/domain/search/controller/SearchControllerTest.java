package com.opensource.docgrid.domain.search.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.rag.dto.RagAnswer;
import com.opensource.docgrid.domain.rag.dto.RagEnqueueOutcome;
import com.opensource.docgrid.domain.rag.service.RagFacade;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.service.SearchFacade;
import com.opensource.docgrid.domain.search.service.query.SearchAnswerQueryService;
import com.opensource.docgrid.domain.search.service.query.SearchConversationQueryService;

import java.math.BigDecimal;

/**
 * #218(비동기 Job 큐 전환) 이후 POST /search는 RagFacade.enqueue()를 호출한다 — NO_CONTEXT는
 * 여전히 즉시 답변을 반환하지만, 검색 후보가 있으면 answer=null·ragStatus=PROCESSING으로 즉시
 * 응답하고 LLM 호출은 기다리지 않는다.
 */
@WebMvcTest(SearchController.class)
@DisplayName("SearchController 테스트")
class SearchControllerTest {

    private static final Long USER_ID = 10L;
    private static final Long QUERY_ID = 100L;
    private static final Long CONVERSATION_ID = 50L;
    private static final String NO_CONTEXT_ANSWER = "관련 문서를 찾지 못했습니다.";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SearchFacade searchFacade;
    @MockitoBean private RagFacade ragFacade;
    @MockitoBean private SearchAnswerQueryService searchAnswerQueryService;
    @MockitoBean private SearchConversationQueryService searchConversationQueryService;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("검색 후보가 모두 제거되면 빈 근거 목록과 NO_CONTEXT 답변을 즉시(SUCCESS) 반환한다")
    void search_noQualifiedCandidates_returnsNoContextResponse() throws Exception {
        SearchRequest request = new SearchRequest("넌 뭐야?", 5, null);
        SearchOutcome outcome = new SearchOutcome(
            SearchResponse.empty(CONVERSATION_ID, QUERY_ID),
            List.of(),
            List.of()
        );
        given(searchFacade.search(USER_ID, request)).willReturn(outcome);
        given(ragFacade.enqueue(CONVERSATION_ID, QUERY_ID, request.queryText(), List.of()))
            .willReturn(RagEnqueueOutcome.done(RagAnswer.noContext(NO_CONTEXT_ANSWER)));

        mockMvc.perform(post("/search")
                .with(csrf())
                .with(authentication(authenticationWithUserId(USER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "queryText": "넌 뭐야?",
                      "topK": 5,
                      "collectionId": null
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.queryId").value(QUERY_ID))
            .andExpect(jsonPath("$.data.conversationId").value(CONVERSATION_ID))
            .andExpect(jsonPath("$.data.results").isEmpty())
            .andExpect(jsonPath("$.data.ragStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.answer").value(NO_CONTEXT_ANSWER))
            .andExpect(jsonPath("$.data.citations").isEmpty());

        then(searchFacade).should().search(USER_ID, request);
        then(ragFacade).should().enqueue(CONVERSATION_ID, QUERY_ID, request.queryText(), List.of());
    }

    @Test
    @DisplayName("검색 후보가 있으면 LLM 호출을 기다리지 않고 ragStatus=PROCESSING, answer=null로 즉시 응답한다")
    void search_withCandidates_returnsProcessingWithoutWaitingForLlm() throws Exception {
        SearchRequest request = new SearchRequest("연차 규정 알려줘", 5, null);
        VectorSearchCandidate candidate = new VectorSearchCandidate(
            1L, 10L, 100L, "청크 내용", 12, "인사규정", new BigDecimal("0.9")
        );
        SearchOutcome outcome = new SearchOutcome(
            SearchResponse.of(CONVERSATION_ID, QUERY_ID, List.of(candidate)),
            List.of(candidate),
            List.of()
        );
        given(searchFacade.search(USER_ID, request)).willReturn(outcome);
        given(ragFacade.enqueue(CONVERSATION_ID, QUERY_ID, request.queryText(), List.of(candidate)))
            .willReturn(RagEnqueueOutcome.stillPending());

        mockMvc.perform(post("/search")
                .with(csrf())
                .with(authentication(authenticationWithUserId(USER_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "queryText": "연차 규정 알려줘",
                      "topK": 5,
                      "collectionId": null
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.queryId").value(QUERY_ID))
            .andExpect(jsonPath("$.data.results").isNotEmpty())
            .andExpect(jsonPath("$.data.ragStatus").value("PROCESSING"))
            .andExpect(jsonPath("$.data.answer").doesNotExist());

        then(ragFacade).should().enqueue(
            CONVERSATION_ID, QUERY_ID, request.queryText(), List.of(candidate)
        );
    }

    private UsernamePasswordAuthenticationToken authenticationWithUserId(Long userId) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
            "user",
            "password",
            List.of()
        );
        authentication.setDetails(userId);
        return authentication;
    }
}
