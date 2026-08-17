package com.opensource.docgrid.domain.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.user.dto.request.AssignRoleRequest;
import com.opensource.docgrid.domain.user.dto.request.ChangeDepartmentRequest;
import com.opensource.docgrid.domain.user.dto.response.AdminUserResponse;
import com.opensource.docgrid.domain.user.dto.response.UserRoleResponse;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.service.command.UserCommandService;
import com.opensource.docgrid.domain.user.service.command.UserRoleCommandService;
import com.opensource.docgrid.domain.user.service.query.AdminUserQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * ADMIN 전용 사용자 목록 조회와 사용자 역할 부여 API를 제공한다.
 */
@Tag(name = "Admin - User", description = "관리자 전용 사용자 관리 API")
@Validated
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRoleCommandService userRoleCommandService;
    private final UserCommandService userCommandService;
    private final AdminUserQueryService adminUserQueryService;

    @Operation(
            summary = "전체 사용자 목록 조회",
            description = "이름·이메일 검색과 부서·상태 필터를 적용해 사용자와 역할 코드를 최근 가입순으로 페이지 조회합니다. " +
                    "status 미입력 시 DELETED 사용자는 제외하며, 비밀번호와 인증 내부 정보는 반환하지 않습니다."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminUserResponse>>> getUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @Positive Long departmentId,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseUtils.ok(adminUserQueryService.getUsers(keyword, departmentId, status, page, size));
    }

    @Operation(summary = "역할 부여", description = "특정 사용자에게 역할을 부여합니다. ADMIN 권한이 필요합니다. 이미 부여된 역할이면 409를 반환합니다.")
    @PostMapping("/{userId}/roles")
    public ResponseEntity<ApiResponse<UserRoleResponse>> assignRole(
            @PathVariable Long userId,
            @Parameter(hidden = true) @CurrentUser Long adminUserId,
            @RequestBody @Valid AssignRoleRequest request) {
        return ResponseUtils.ok(userRoleCommandService.assignRole(userId, adminUserId, request));
    }

    @Operation(
            summary = "사용자 부서 변경",
            description = "특정 사용자의 소속 부서를 변경합니다. ADMIN 권한이 필요합니다. "
                    + "존재하지 않거나 비활성 상태인 부서면 400을 반환합니다."
    )
    @PatchMapping("/{userId}/department")
    public ResponseEntity<ApiResponse<AdminUserResponse>> changeDepartment(
            @PathVariable Long userId,
            @RequestBody @Valid ChangeDepartmentRequest request) {
        return ResponseUtils.ok(userCommandService.changeDepartment(userId, request));
    }
}
