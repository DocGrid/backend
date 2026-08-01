package com.opensource.docgrid.domain.collection.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.collection.dto.request.AddDocumentRequest;
import com.opensource.docgrid.domain.collection.dto.request.CreateCollectionRequest;
import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.service.command.CollectionCommandService;
import com.opensource.docgrid.domain.collection.service.query.CollectionQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Collection", description = "컬렉션 관련 API")
@RestController
@RequestMapping("/collections")
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionCommandService collectionCommandService;
    private final CollectionQueryService collectionQueryService;

    @Operation(
            summary = "내 컬렉션 목록 조회",
            description = "현재 로그인한 사용자가 소유한 ACTIVE 상태의 컬렉션 목록을 반환합니다."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<List<CollectionResponse>>> getMyCollections(
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ResponseUtils.ok(collectionQueryService.getMyCollections(userId));
    }

    @Operation(
            summary = "컬렉션 삭제",
            description = "컬렉션을 soft delete합니다. 소유자(owner)만 가능합니다. " +
                    "소속 권한(collection_permissions)이 모두 삭제되고, USER 대상 권한이 있었다면 캐시도 무효화됩니다."
    )
    @DeleteMapping("/{collectionId}")
    public ResponseEntity<ApiResponse<Void>> deleteCollection(
            @PathVariable Long collectionId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        collectionCommandService.deleteCollection(collectionId, userId);
        return ResponseUtils.noContent();
    }

    @Operation(
            summary = "컬렉션에서 문서 제거",
            description = "컬렉션에서 특정 문서를 제거합니다. 소유자(owner)만 가능합니다. " +
                    "해당 문서에 대해 이 컬렉션 권한으로 캐시된 USER 접근 권한이 무효화됩니다."
    )
    @DeleteMapping("/{collectionId}/documents/{documentId}")
    public ResponseEntity<ApiResponse<Void>> removeDocument(
            @PathVariable Long collectionId,
            @PathVariable Long documentId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        collectionCommandService.removeDocument(collectionId, documentId, userId);
        return ResponseUtils.noContent();
    }

    @Operation(
            summary = "컬렉션 생성",
            description = "새 컬렉션을 생성합니다. 생성자가 소유자(owner)로 설정됩니다. visibility 미입력 시 PRIVATE으로 생성됩니다."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<CollectionResponse>> createCollection(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @RequestBody @Valid CreateCollectionRequest request) {
        return ResponseUtils.created(collectionCommandService.createCollection(userId, request));
    }

    @Operation(
            summary = "컬렉션 단건 조회",
            description = "컬렉션 ID로 컬렉션 정보를 조회합니다. 소유자, PUBLIC 컬렉션, 또는 권한을 부여받은 사용자만 조회 가능합니다."
    )
    @GetMapping("/{collectionId}")
    public ResponseEntity<ApiResponse<CollectionResponse>> getCollection(
            @PathVariable Long collectionId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ResponseUtils.ok(collectionQueryService.getCollection(userId, collectionId));
    }

    @Operation(
            summary = "컬렉션에 문서 추가",
            description = "컬렉션에 문서를 추가합니다. 컬렉션 쓰기 권한(WRITE, 소유자 포함)이 있는 사용자만 가능합니다. 이미 추가된 문서면 409를 반환합니다."
    )
    @PostMapping("/{collectionId}/documents")
    public ResponseEntity<ApiResponse<CollectionDocumentResponse>> addDocument(
            @PathVariable Long collectionId,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @RequestBody @Valid AddDocumentRequest request) {
        return ResponseUtils.created(collectionCommandService.addDocument(collectionId, userId, request));
    }
}
