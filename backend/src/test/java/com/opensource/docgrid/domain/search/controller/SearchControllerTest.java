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
import com.opensource.docgrid.domain.rag.service.RagFacade;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.service.SearchFacade;

/**
 * 검색 후보가 모두 제거된 경우의 인증 사용자 전달과 NO_CONTEXT 공개 응답 계약을 검증한다.
 */
@WebMvcTest(SearchController.class)
@DisplayName("SearchController 테스트")
class SearchControllerTest {

    private static final Long USER_ID = 10L;
    private static final Long QUERY_ID = 100L;
    private static final String NO_CONTEXT_ANSWER = "관련 문서를 찾지 못했습니다.";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SearchFacade searchFacade;
    @MockitoBean private RagFacade ragFacade;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("검색 후보가 모두 제거되면 빈 근거 목록과 NO_CONTEXT 답변을 반환한다")
    void search_noQualifiedCandidates_returnsNoContextResponse() throws Exception {
        SearchRequest request = new SearchRequest("넌 뭐야?", 5, null);
        SearchOutcome outcome = new SearchOutcome(
            SearchResponse.empty(QUERY_ID),
            List.of(),
            List.of()
        );
        given(searchFacade.search(USER_ID, request)).willReturn(outcome);
        given(ragFacade.generate(QUERY_ID, request.queryText(), List.of(), List.of()))
            .willReturn(RagAnswer.noContext(NO_CONTEXT_ANSWER));

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
            .andExpect(jsonPath("$.data.results").isEmpty())
            .andExpect(jsonPath("$.data.answer").value(NO_CONTEXT_ANSWER))
            .andExpect(jsonPath("$.data.citations").isEmpty());

        then(searchFacade).should().search(USER_ID, request);
        then(ragFacade).should().generate(QUERY_ID, request.queryText(), List.of(), List.of());
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
