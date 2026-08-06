package com.opensource.docgrid.domain.mcp.tool;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.service.SearchFacade;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DocGridMcpTools {

    private static final int MAX_QUERY_LENGTH = 2000;
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;

    private final SearchFacade searchFacade;
    private final ObjectMapper objectMapper;

    @McpTool(name = "search_documents",
        description = "사용자 질문과 관련된 문서 chunk를 벡터 검색으로 찾는다. 권한이 있는 문서만 반환된다.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public String searchDocuments(
            @McpToolParam(description = "검색어", required = true) String query,
            @McpToolParam(description = "반환할 최대 결과 수 (기본 5, 1~20)", required = false) Integer topK) {
        // 1. 입력 검증 — 타입/필수값은 SDK가 이미 처리, 여기서는 비즈니스 규칙(길이/범위)만 확인
        validateSearchInput(query, topK);

        // 2. McpApiKeyAuthFilter가 SecurityContext에 저장해둔 사용자 식별
        Long userId = currentUserId();

        // 3. 검색 실행 — 권한 pre-filter + live check는 SearchFacade 내부에서 수행 (별도 구현 불필요)
        SearchOutcome outcome = searchFacade.search(userId, new SearchRequest(query, topK, null));

        // 4. MCP 클라이언트가 파싱할 수 있도록 결과를 JSON 문자열로 직렬화 (SDK가 텍스트 콘텐츠로 자동 래핑)
        return toJson(outcome.response().results());
    }

    @McpTool(name = "get_document_detail",
        description = "특정 문서의 메타데이터와 현재 버전 정보를 조회한다. 권한이 있는 문서만 조회 가능하다.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public String getDocumentDetail(
            @McpToolParam(description = "문서 ID", required = true) Long documentId) {
        // TODO: PermissionQueryService + DocumentQueryService 연동 (다음 이슈에서 구현)
        return "not implemented";
    }

    @McpTool(name = "get_indexing_status",
        description = "특정 문서 또는 버전의 인덱싱 상태(PENDING/PROCESSING/INDEXED/FAILED)를 조회한다.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public String getIndexingStatus(
            @McpToolParam(description = "문서 ID", required = false) Long documentId,
            @McpToolParam(description = "버전 ID", required = false) Long versionId) {
        // TODO: PermissionQueryService + DocumentQueryService 연동 (다음 이슈에서 구현)
        return "not implemented";
    }

    private void validateSearchInput(String query, Integer topK) {
        // SDK가 JSON Schema의 required를 강제하지 않음 — 실측 결과 query=null로 호출부까지 그대로 넘어옴
        if (query == null || query.isBlank()) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER, "query는 필수입니다.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER,
                    "query는 " + MAX_QUERY_LENGTH + "자 이내여야 합니다.");
        }
        if (topK != null && (topK < MIN_TOP_K || topK > MAX_TOP_K)) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER,
                    "topK는 " + MIN_TOP_K + "~" + MAX_TOP_K + " 사이여야 합니다.");
        }
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof Long userId)) {
            throw new DocGridException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new DocGridException(ErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }
}
