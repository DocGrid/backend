package com.opensource.docgrid.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.service.SearchFacade;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocGridMcpTools 단위 테스트")
class DocGridMcpToolsTest {

    private static final Long USER_ID = 1L;

    private DocGridMcpTools docGridMcpTools;

    @Mock
    private SearchFacade searchFacade;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpAuthentication() {
        // ObjectMapper는 실제 직렬화 결과를 검증해야 하므로 목이 아닌 실제 인스턴스를 사용한다
        docGridMcpTools = new DocGridMcpTools(searchFacade, objectMapper);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("mcp-client", null, List.of());
        authentication.setDetails(USER_ID);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("정상 케이스: 유효한 query로 검색하면 SearchFacade 결과가 JSON으로 직렬화되어 반환된다")
    void searchDocuments_returnsJson_whenInputValid() {
        // Given
        SearchOutcome outcome = new SearchOutcome(SearchResponse.empty(1L), List.of(), List.of());
        given(searchFacade.search(eq(USER_ID), any(SearchRequest.class))).willReturn(outcome);

        // When
        String result = docGridMcpTools.searchDocuments("연차 규정", 5);

        // Then — searchDocuments는 SearchResponse 전체가 아니라 results 목록만 직렬화한다
        assertThat(result).isEqualTo("[]");
    }

    @Test
    @DisplayName("예외 케이스: query가 null이면 INVALID_PARAMETER 예외가 발생한다")
    void searchDocuments_throws_when_queryNull() {
        assertThatThrownBy(() -> docGridMcpTools.searchDocuments(null, 5))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("예외 케이스: query가 공백뿐이면 INVALID_PARAMETER 예외가 발생한다")
    void searchDocuments_throws_when_queryBlank() {
        assertThatThrownBy(() -> docGridMcpTools.searchDocuments("   ", 5))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("예외 케이스: query가 2000자를 초과하면 INVALID_PARAMETER 예외가 발생한다")
    void searchDocuments_throws_when_queryTooLong() {
        String tooLong = "a".repeat(2001);

        assertThatThrownBy(() -> docGridMcpTools.searchDocuments(tooLong, 5))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("예외 케이스: topK가 범위(1~20)를 벗어나면 INVALID_PARAMETER 예외가 발생한다")
    void searchDocuments_throws_when_topKOutOfRange() {
        assertThatThrownBy(() -> docGridMcpTools.searchDocuments("query", 21))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);

        assertThatThrownBy(() -> docGridMcpTools.searchDocuments("query", 0))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("예외 케이스: 인증 정보가 없으면 UNAUTHORIZED 예외가 발생한다")
    void searchDocuments_throws_when_unauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> docGridMcpTools.searchDocuments("query", 5))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }
}
