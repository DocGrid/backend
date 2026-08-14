package com.opensource.docgrid.domain.document.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.document.dto.request.UpdateDocumentMetadataRequest;
import com.opensource.docgrid.domain.document.service.command.DocumentCommandService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 문서 Metadata 수정과 soft delete HTTP 경계를 제공한다.
 */
@Tag(name = "Document", description = "문서 관련 API")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentCommandController {

    private final DocumentCommandService documentCommandService;

    @Operation(
        summary = "문서 정보 수정",
        description = "문서 제목과 설명을 함께 변경합니다. 문서 WRITE 권한이 필요하며, "
            + "설명에 null 또는 공백을 보내면 기존 설명을 제거합니다. 과거 버전의 제목 이력은 변경하지 않습니다."
    )
    @PatchMapping("/{documentId}")
    public ResponseEntity<ApiResponse<Void>> updateDocumentMetadata(
        @PathVariable Long documentId,
        @Valid @RequestBody UpdateDocumentMetadataRequest request,
        @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        documentCommandService.updateMetadata(userId, documentId, request);
        return ResponseUtils.noContent();
    }

    @Operation(
        summary = "문서 삭제",
        description = "문서를 soft delete해 조회와 검색에서 제외합니다. 문서 ADMIN 권한이 필요하며, "
            + "원본 파일과 버전 이력은 보존되고 검색 Vector는 비동기 동기화 작업으로 비활성화됩니다."
    )
    @DeleteMapping("/{documentId}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(
        @PathVariable Long documentId,
        @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        documentCommandService.deleteDocument(userId, documentId);
        return ResponseUtils.noContent();
    }
}
