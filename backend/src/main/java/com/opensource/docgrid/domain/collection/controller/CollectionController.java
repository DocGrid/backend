package com.opensource.docgrid.domain.collection.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.collection.dto.request.AddDocumentRequest;
import com.opensource.docgrid.domain.collection.dto.request.CreateCollectionRequest;
import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentListItemResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.service.command.CollectionCommandService;
import com.opensource.docgrid.domain.collection.service.query.CollectionQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * 컬렉션 생성·조회·삭제와 컬렉션 문서 구성 및 읽기 가능한 문서 목록 API를 제공한다.
 */
@Tag(name = "Collection", description = "컬렉션 관련 API")
@Validated
@RestController
@RequestMapping("/collections")
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionCommandService collectionCommandService;
    private final CollectionQueryService collectionQueryService;

    @Operation(
            summary = "컬렉션 목록 조회",
            description = "현재 로그인한 사용자가 읽을 수 있는 ACTIVE 상태의 컬렉션을 최신 생성순으로 페이지 조회합니다. " +
                    "소유한 컬렉션, PUBLIC 컬렉션, 직접·역할·부서 단위로 권한을 부여받은 컬렉션(부모 컬렉션 상속 포함)을 모두 포함합니다. " +
                    "keyword를 입력하면 이름·설명에 포함된 것만 필터링합니다."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CollectionResponse>>> getCollections(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseUtils.ok(collectionQueryService.getCollections(userId, keyword, page, size));
    }

    @Operation(
            summary = "컬렉션 삭제",
            description = "컬렉션을 soft delete합니다. 소유자(owner)만 가능합니다. " +
                    "하위 컬렉션 전체와 그 안의 문서 매핑까지 함께 삭제됩니다(cascade). " +
                    "대상 전체의 소속 권한(collection_permissions)이 모두 삭제되고, USER 대상 권한이 있었다면 캐시도 무효화됩니다."
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
            summary = "직계 자식 컬렉션 목록 조회",
            description = "이 컬렉션 바로 아래에 있는 하위 컬렉션 목록을 반환합니다. 하위 컬렉션 자체까지만 반환하며, " +
                    "더 아래 단계를 보려면 반환된 하위 컬렉션 ID로 이 API를 다시 호출해야 합니다."
    )
    @GetMapping("/{collectionId}/children")
    public ResponseEntity<ApiResponse<List<CollectionResponse>>> getChildren(
            @PathVariable Long collectionId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ResponseUtils.ok(collectionQueryService.getChildren(userId, collectionId));
    }

    @Operation(
            summary = "컬렉션 문서 목록 조회",
            description = "컬렉션을 읽을 수 있는 사용자가 개별 문서 읽기 권한도 가진 항목만 추가 최신순으로 페이지 조회합니다. " +
                    "숨김 문서는 응답 데이터와 전체 개수에 포함하지 않습니다."
    )
    @GetMapping("/{collectionId}/documents")
    public ResponseEntity<ApiResponse<PageResponse<CollectionDocumentListItemResponse>>> getCollectionDocuments(
            @PathVariable Long collectionId,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseUtils.ok(collectionQueryService.getCollectionDocuments(userId, collectionId, page, size));
    }

    @Operation(
            summary = "컬렉션에 문서 추가",
            description = "컬렉션에 문서를 추가합니다. 컬렉션 쓰기 권한(WRITE 또는 ADMIN, 소유자 포함)이 있는 사용자만 가능합니다. 이미 추가된 문서면 409를 반환합니다."
    )
    @PostMapping("/{collectionId}/documents")
    public ResponseEntity<ApiResponse<CollectionDocumentResponse>> addDocument(
            @PathVariable Long collectionId,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @RequestBody @Valid AddDocumentRequest request) {
        return ResponseUtils.created(collectionCommandService.addDocument(collectionId, userId, request));
    }
}
