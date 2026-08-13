package com.opensource.docgrid.domain.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.user.dto.request.AssignRoleRequest;
import com.opensource.docgrid.domain.user.dto.response.UserRoleResponse;
import com.opensource.docgrid.domain.user.service.command.UserRoleCommandService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin - User", description = "관리자 전용 사용자 관리 API")
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRoleCommandService userRoleCommandService;

    @Operation(summary = "역할 부여", description = "특정 사용자에게 역할을 부여합니다. ADMIN 권한이 필요합니다. 이미 부여된 역할이면 409를 반환합니다.")
    @PostMapping("/{userId}/roles")
    public ResponseEntity<ApiResponse<UserRoleResponse>> assignRole(
            @PathVariable Long userId,
            @Parameter(hidden = true) @CurrentUser Long adminUserId,
            @RequestBody @Valid AssignRoleRequest request) {
        return ResponseUtils.ok(userRoleCommandService.assignRole(userId, adminUserId, request));
    }
}
