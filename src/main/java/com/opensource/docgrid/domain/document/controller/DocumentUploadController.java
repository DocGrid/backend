package com.opensource.docgrid.domain.document.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.request.DocumentVersionUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.service.DocumentVersionUploadFacade;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Document", description = "문서 관련 API")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentUploadController {

    private final DocumentUploadFacade documentUploadFacade;
    private final DocumentVersionUploadFacade documentVersionUploadFacade;

    @Operation(
        summary = "문서 업로드 접수",
        description = "TXT, Markdown, PDF 또는 DOCX 원본 파일을 저장하고 비동기 인덱싱 작업을 생성합니다. "
            + "파싱과 임베딩은 수행하지 않습니다. 확장자와 Content-Type이 함께 맞아야 하며, "
            + "구형 DOC 형식은 지원하지 않습니다."
    )
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentUploadResponse>> upload(
        @Valid @ModelAttribute DocumentUploadRequest request,
        @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        return ResponseUtils.created(documentUploadFacade.upload(userId, request));
    }

    @Operation(
        summary = "문서 새 버전 업로드 접수",
        description = "기존 문서에 새 파일 버전을 등록하고 비동기 인덱싱 작업을 생성합니다."
    )
    @PostMapping(value = "/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentVersionUploadResponse>> uploadVersion(
        @PathVariable Long documentId,
        @Valid @ModelAttribute DocumentVersionUploadRequest request,
        @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        return ResponseUtils.created(documentVersionUploadFacade.upload(userId, documentId, request));
    }
}
