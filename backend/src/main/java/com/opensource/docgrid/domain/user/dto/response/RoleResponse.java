package com.opensource.docgrid.domain.user.dto.response;

import com.opensource.docgrid.domain.user.entity.Role;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 역할 목록 조회 API 응답 DTO.
 *
 * <p>{@link Role} 엔티티를 Controller 계층에 직접 노출하지 않기 위한 변환 경계다.
 */
public record RoleResponse(
        @Schema(description = "역할 ID") Long id,
        @Schema(description = "역할명") String name,
        @Schema(description = "역할 코드") String code
) {
    public static RoleResponse from(Role role) {
        return new RoleResponse(role.getId(), role.getName(), role.getCode());
    }
}
