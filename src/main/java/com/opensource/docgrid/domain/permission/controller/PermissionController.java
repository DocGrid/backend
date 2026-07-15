package com.opensource.docgrid.domain.permission.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.dto.response.CollectionPermissionResponse;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionResponse;
import com.opensource.docgrid.domain.permission.service.command.CollectionPermissionCommandService;
import com.opensource.docgrid.domain.permission.service.command.DocumentPermissionCommandService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Permission", description = "권한 관련 API")
@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final CollectionPermissionCommandService collectionPermissionCommandService;
    private final DocumentPermissionCommandService documentPermissionCommandService;

    @Operation(
            summary = "컬렉션 권한 부여",
            description = "컬렉션에 USER/ROLE/DEPARTMENT 단위로 권한을 부여합니다. 컬렉션 소유자(owner)만 가능합니다. " +
                    "targetType에 맞는 ID 필드(userId/roleId/departmentId) 하나만 입력해야 합니다. " +
                    "USER 대상인 경우 컬렉션 내 문서에 대한 접근 캐시가 즉시 갱신됩니다."
    )
    @PostMapping("/collections/{collectionId}")
    public ResponseEntity<ApiResponse<CollectionPermissionResponse>> grantCollectionPermission(
            @PathVariable Long collectionId,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @RequestBody @Valid GrantPermissionRequest request) {
        return ResponseUtils.created(
                collectionPermissionCommandService.grantPermission(collectionId, userId, request));
    }

    @Operation(
            summary = "컬렉션 권한 회수",
            description = "부여된 컬렉션 권한을 회수합니다. 컬렉션 소유자(owner)만 가능합니다. " +
                    "USER 대상 권한이었다면 접근 캐시도 즉시 무효화됩니다."
    )
    @DeleteMapping("/collections/{collectionId}/{permissionId}")
    public ResponseEntity<ApiResponse<Void>> revokeCollectionPermission(
            @PathVariable Long collectionId,
            @PathVariable Long permissionId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        collectionPermissionCommandService.revokePermission(collectionId, permissionId, userId);
        return ResponseUtils.noContent();
    }

    @Operation(
            summary = "문서 예외 권한 부여",
            description = "특정 문서 하나에만 적용되는 예외 권한을 부여합니다. 문서 소유자(owner)만 가능합니다. " +
                    "기본 권한은 collection_permissions로 관리하고, 이 API는 예외 케이스에만 최소한으로 사용하세요. " +
                    "USER 대상인 경우 해당 문서의 접근 캐시가 즉시 갱신됩니다."
    )
    @PostMapping("/documents/{documentId}")
    public ResponseEntity<ApiResponse<DocumentPermissionResponse>> grantDocumentPermission(
            @PathVariable Long documentId,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @RequestBody @Valid GrantPermissionRequest request) {
        return ResponseUtils.created(
                documentPermissionCommandService.grantPermission(documentId, userId, request));
    }

    @Operation(
            summary = "문서 예외 권한 회수",
            description = "부여된 문서 예외 권한을 회수합니다. 문서 소유자(owner)만 가능합니다. " +
                    "USER 대상 권한이었다면 해당 문서의 접근 캐시도 즉시 무효화됩니다."
    )
    @DeleteMapping("/documents/{documentId}/{permissionId}")
    public ResponseEntity<ApiResponse<Void>> revokeDocumentPermission(
            @PathVariable Long documentId,
            @PathVariable Long permissionId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        documentPermissionCommandService.revokePermission(documentId, permissionId, userId);
        return ResponseUtils.noContent();
    }
}
