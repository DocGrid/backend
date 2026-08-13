package com.opensource.docgrid.domain.permission.dto.request;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "권한 부여 요청")
public record GrantPermissionRequest(
        @Schema(description = "권한 부여 대상 타입 (USER / ROLE / DEPARTMENT)") @NotNull PermissionTargetType targetType,
        @Schema(description = "대상 사용자 ID (targetType=USER일 때만 입력)") Long userId,
        @Schema(description = "대상 역할 ID (targetType=ROLE일 때만 입력)") Long roleId,
        @Schema(description = "대상 부서 ID (targetType=DEPARTMENT일 때만 입력)") Long departmentId,
        @Schema(description = "권한 종류 (READ / WRITE / ADMIN)") @NotNull PermissionType permissionType,
        @Schema(description = "권한 만료 시각 (null이면 만료 없음)") LocalDateTime expiresAt
) {
}
