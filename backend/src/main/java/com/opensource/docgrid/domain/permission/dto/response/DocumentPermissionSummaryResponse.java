package com.opensource.docgrid.domain.permission.dto.response;

import java.util.List;

import com.opensource.docgrid.domain.permission.enums.PermissionSourceType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "내 문서 권한 요약")
public record DocumentPermissionSummaryResponse(
        @Schema(description = "문서 ID") Long documentId,
        @Schema(description = "읽기 권한") boolean canRead,
        @Schema(description = "쓰기 권한") boolean canWrite,
        @Schema(description = "관리 권한") boolean canAdmin,
        @Schema(description = "권한 부여 경로 (OWNER/PUBLIC/USER_CACHE/ROLE/DEPARTMENT)") List<PermissionSourceType> sources
) {
}
