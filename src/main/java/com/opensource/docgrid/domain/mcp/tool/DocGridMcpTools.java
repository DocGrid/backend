package com.opensource.docgrid.domain.mcp.tool;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class DocGridMcpTools {

    @McpTool(name = "search_documents",
        description = "사용자 질문과 관련된 문서 chunk를 벡터 검색으로 찾는다. 권한이 있는 문서만 반환된다.")
    public String searchDocuments(
            @McpToolParam(description = "검색어", required = true) String query,
            @McpToolParam(description = "반환할 최대 결과 수 (기본 5, 1~20)", required = false) Integer topK) {
        // TODO: SearchFacade 연동 (다음 이슈에서 구현)
        return "not implemented";
    }

    @McpTool(name = "get_document_detail",
        description = "특정 문서의 메타데이터와 현재 버전 정보를 조회한다. 권한이 있는 문서만 조회 가능하다.")
    public String getDocumentDetail(
            @McpToolParam(description = "문서 ID", required = true) Long documentId) {
        // TODO: PermissionQueryService + DocumentQueryService 연동 (다음 이슈에서 구현)
        return "not implemented";
    }

    @McpTool(name = "get_indexing_status",
        description = "특정 문서 또는 버전의 인덱싱 상태(PENDING/PROCESSING/INDEXED/FAILED)를 조회한다.")
    public String getIndexingStatus(
            @McpToolParam(description = "문서 ID", required = false) Long documentId,
            @McpToolParam(description = "버전 ID", required = false) Long versionId) {
        // TODO: PermissionQueryService + DocumentQueryService 연동 (다음 이슈에서 구현)
        return "not implemented";
    }
}
