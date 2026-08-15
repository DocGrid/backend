package com.opensource.docgrid.domain.mcp.tool;

import java.util.List;
import java.util.function.Function;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.service.query.DocumentQueryService;
import com.opensource.docgrid.domain.mcp.dto.response.DocumentDetailResponse;
import com.opensource.docgrid.domain.mcp.security.McpRateLimiter;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResultItem;
import com.opensource.docgrid.domain.search.service.SearchFacade;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DocGridMcpTools {

    /*
     전체 MCP 도구 호출에 공통 적용되는 제약 조건
        1) query 길이 제한: 2000자
        2) topK 범위 제한: 1~20
        3) chunkText 길이 제한: 1000자 (검색 결과 반환 시)
        4) search_documents 호출 제한: 분당 20회
        5) get_document_detail 호출 제한: 분당 30회
        6) get_indexing_status 호출 제한: 분당 30회
     */
    private static final int MAX_QUERY_LENGTH = 2000;
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;
    private static final int MAX_CHUNK_TEXT_LENGTH = 1000;

    private static final int SEARCH_RATE_LIMIT_PER_MINUTE = 20;
    private static final int DOCUMENT_RATE_LIMIT_PER_MINUTE = 30;

    private final SearchFacade searchFacade;
    private final DocumentRepository documentRepository;
    private final PermissionQueryService permissionQueryService;
    private final DocumentQueryService documentQueryService;
    private final McpRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public DocGridMcpTools(SearchFacade searchFacade, DocumentRepository documentRepository,
            PermissionQueryService permissionQueryService, DocumentQueryService documentQueryService,
            McpRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.searchFacade = searchFacade;
        this.documentRepository = documentRepository;
        this.permissionQueryService = permissionQueryService;
        this.documentQueryService = documentQueryService;
        this.rateLimiter = rateLimiter;
        // MCP 응답은 null 필드를 제외한다. 앱 전체가 공유하는 ObjectMapper Bean을 직접 바꾸면
        // 다른 REST API 응답에도 영향을 주므로, 이 클래스 전용 복사본에만 설정을 적용한다.
        this.objectMapper = objectMapper.copy().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    }

    @McpTool(name = "search_documents",
        description = "사용자 질문과 관련된 문서 chunk를 벡터 검색으로 찾는다. 권한이 있는 문서만 반환된다.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public String searchDocuments(
            @McpToolParam(description = "검색어", required = true) String query,
            @McpToolParam(description = "반환할 최대 결과 수 (기본 5, 1~20)", required = false) Integer topK) {

        // SDK는 required(필수값)를 강제하지 않음이 실측으로 확인됨 (query=null로 그대로 호출됨)
        // → null/blank 여부와 비즈니스 규칙(길이/범위)을 전부 여기서 직접 검증한다
        validateSearchInput(query, topK);

        return executeTool("search_documents", SEARCH_RATE_LIMIT_PER_MINUTE, userId -> {
            // 권한 pre-filter + live check는 SearchFacade 내부에서 수행 (별도 구현 불필요)
            SearchOutcome outcome = searchFacade.search(userId, new SearchRequest(query, topK, null));
            return truncateChunkText(outcome.response().results());
        });
    }

    @McpTool(name = "get_document_detail",
        description = "특정 문서의 메타데이터와 현재 버전 정보를 조회한다. 권한이 있는 문서만 조회 가능하다.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public String getDocumentDetail(
            @McpToolParam(description = "문서 ID", required = true) Long documentId) {
        requireDocumentId(documentId);

        return executeTool("get_document_detail", DOCUMENT_RATE_LIMIT_PER_MINUTE, userId -> {
            // 권한 확인 — false면 문서 존재 여부를 노출하지 않기 위해 조회 전에 차단
            if (!permissionQueryService.canReadDocument(userId, documentId)) {
                throw new DocGridException(ErrorCode.PERMISSION_DENIED);
            }

            // title/status/currentVersion/updatedAt은 Document 엔티티에 이미 있어 직접 사용.
            // currentVersion은 LAZY라 OSIV가 꺼진 /mcp 경로에서는 findById만 쓰면 트랜잭션
            // 종료 후 LazyInitializationException이 나므로 JOIN FETCH 쿼리를 사용한다.
            Document document = documentRepository.findByIdWithCurrentVersion(documentId)
                    .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

            Integer currentVersionNo = document.getCurrentVersion() != null
                    ? document.getCurrentVersion().getVersionNo()
                    : null;
            return new DocumentDetailResponse(
                    document.getId(), document.getTitle(), currentVersionNo,
                    document.getStatus(), document.getUpdatedAt()
            );
        });
    }

    @McpTool(name = "get_indexing_status",
        description = "특정 문서의 인덱싱 상태(PENDING/PROCESSING/INDEXED/FAILED)를 조회한다.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public String getIndexingStatus(
            @McpToolParam(description = "문서 ID", required = true) Long documentId) {
        requireDocumentId(documentId);

        // DocumentQueryService.getDocumentStatus가 내부에서 권한체크까지 수행 (그대로 재사용)
        return executeTool("get_indexing_status", DOCUMENT_RATE_LIMIT_PER_MINUTE,
                userId -> documentQueryService.getDocumentStatus(userId, documentId));
    }

    /**
     * 도구 3종에 공통되는 실행 흐름(사용자 식별 → rate limit → 실행 → 안전한 예외 변환 → JSON 직렬화)을 담당한다.
     * DocGridException은 이미 안전한 메시지를 담고 있어 그대로 전파하고, 그 외 예상치 못한 예외는
     * 내부 정보가 클라이언트에 노출되지 않도록 INTERNAL_SERVER_ERROR로 치환한다.
     */
    private String executeTool(String toolName, int limitPerMinute, Function<Long, Object> action) {
        // 1. McpApiKeyAuthFilter가 SecurityContext에 저장해둔 사용자 식별
        Long userId = currentUserId();
        // 2. 분당 호출 횟수 제한 확인
        rateLimiter.checkLimit(userId, toolName, limitPerMinute);

        try {
            // 3. 실제 도구 로직 실행
            Object result = action.apply(userId);
            // 4. JSON으로 직렬화
            return toJson(result);
        } catch (DocGridException e) {
            // 이미 안전한 메시지를 담고 있으므로 그대로 전파
            throw e;
        } catch (Exception e) {
            // 예상치 못한 예외는 내부 정보가 노출되지 않도록 표준 메시지로 치환
            log.error("MCP 도구 실행 중 예상하지 못한 오류 toolName={}", toolName, e);
            throw new DocGridException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // 검색 결과 chunkText가 너무 길면 잘라서 반환 (MCP 도구 호출 시 JSON 응답 크기 제한)
    private List<SearchResultItem> truncateChunkText(List<SearchResultItem> items) {
        return items.stream()
                .map(item -> item.chunkText() != null && item.chunkText().length() > MAX_CHUNK_TEXT_LENGTH
                        ? new SearchResultItem(item.rank(), item.documentId(), item.chunkId(), item.documentTitle(),
                                item.chunkText().substring(0, MAX_CHUNK_TEXT_LENGTH),
                                item.pageNo(), item.similarityScore())
                        : item)
                .toList();
    }

    // MCP 도구 호출 시 documentId는 필수값이므로 null이면 예외를 던진다. )
    private void requireDocumentId(Long documentId) {
        if (documentId == null) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER, "documentId는 필수입니다.");
        }
    }

    // search_documents 호출 시 query와 topK를 검증한다. query는 null/blank 불가, 길이 제한, topK는 범위 제한.
    private void validateSearchInput(String query, Integer topK) {
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

    // SecurityContext에서 현재 인증된 사용자의 ID를 가져온다. 인증 정보가 없거나 ID가 Long이 아니면 UNAUTHORIZED 예외를 던진다.
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof Long userId)) {
            throw new DocGridException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    // Jackson ObjectMapper를 사용해 객체를 JSON 문자열로 직렬화한다. 실패하면 INTERNAL_SERVER_ERROR 예외를 던진다.
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new DocGridException(ErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }
}
