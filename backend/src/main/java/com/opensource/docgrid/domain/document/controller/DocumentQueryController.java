package com.opensource.docgrid.domain.document.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentSummaryResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.service.query.DocumentQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Tag(name = "Document", description = "문서 관련 API")
@Validated
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentQueryController {

    private final DocumentQueryService documentQueryService;

    @Operation(
        summary = "내 문서 목록 조회",
        description = "로그인한 사용자가 읽을 수 있는 문서를 최신 등록순으로 조회합니다. "
            + "소유한 문서, PUBLIC 문서, 문서·컬렉션 권한을 부여받은 문서가 모두 포함됩니다. "
            + "status를 지정하면 해당 상태만 조회하며, 미지정 시 삭제된 문서를 제외한 전체를 반환합니다. "
            + "size는 1~100까지 지정할 수 있습니다."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<DocumentSummaryResponse>>> getMyDocuments(
        @Parameter(hidden = true) @CurrentUser Long userId,
        @RequestParam(required = false) DocumentStatus status,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseUtils.ok(documentQueryService.getMyDocuments(userId, status, page, size));
    }

    @Operation(
        summary = "문서 인덱싱 상태 조회",
        description = "현재 검색 가능한 INDEXED 버전과 처리 중인 버전 및 임베딩 작업 상태를 함께 조회합니다. "
            + "최초 버전이 아직 처리 중이면 currentVersion은 null입니다. 문서 읽기 권한이 필요합니다."
    )
    @GetMapping("/{documentId}/status")
    public ResponseEntity<ApiResponse<DocumentStatusResponse>> getDocumentStatus(
        @PathVariable Long documentId,
        @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        return ResponseUtils.ok(documentQueryService.getDocumentStatus(userId, documentId));
    }
}
