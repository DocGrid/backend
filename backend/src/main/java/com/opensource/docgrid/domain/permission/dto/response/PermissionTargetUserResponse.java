package com.opensource.docgrid.domain.permission.dto.response;

import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.User;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 권한 부여 대상 검색에서 동명이인을 구분하는 데 필요한 최소 사용자 정보만 노출한다.
 */
@Schema(description = "권한 부여 대상 사용자 검색 항목")
public record PermissionTargetUserResponse(
    @Schema(description = "사용자 ID") Long userId,
    @Schema(description = "이름") String name,
    @Schema(description = "이메일") String email,
    @Schema(description = "부서 ID") Long departmentId,
    @Schema(description = "부서명") String departmentName
) {
    public static PermissionTargetUserResponse from(User user) {
        Department department = user.getDepartment();
        return new PermissionTargetUserResponse(
            user.getId(),
            user.getName(),
            user.getEmail(),
            department != null ? department.getId() : null,
            department != null ? department.getName() : null
        );
    }
}
