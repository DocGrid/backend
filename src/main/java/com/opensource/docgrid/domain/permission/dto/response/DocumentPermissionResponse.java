package com.opensource.docgrid.domain.permission.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "문서 권한 부여 결과")
public record DocumentPermissionResponse(
        @Schema(description = "권한 ID") Long permissionId,
        @Schema(description = "문서 ID") Long documentId,
        @Schema(description = "권한 대상 타입") PermissionTargetType targetType,
        @Schema(description = "대상 사용자 ID") Long userId,
        @Schema(description = "대상 역할 ID") Long roleId,
        @Schema(description = "대상 부서 ID") Long departmentId,
        @Schema(description = "권한 종류") PermissionType permissionType,
        @Schema(description = "읽기 권한") boolean canRead,
        @Schema(description = "쓰기 권한") boolean canWrite,
        @Schema(description = "관리 권한") boolean canAdmin,
        @Schema(description = "부여한 사용자 ID") Long grantedBy,
        @Schema(description = "부여 시각") LocalDateTime grantedAt,
        @Schema(description = "만료 시각") LocalDateTime expiresAt
) {
}
