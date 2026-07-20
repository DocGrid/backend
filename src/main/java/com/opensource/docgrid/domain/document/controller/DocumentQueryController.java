package com.opensource.docgrid.domain.document.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.service.query.DocumentQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Document", description = "문서 관련 API")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentQueryController {

    private final DocumentQueryService documentQueryService;

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
