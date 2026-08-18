package com.opensource.docgrid.domain.user.dto.response;

import com.opensource.docgrid.domain.user.entity.Role;

import io.swagger.v3.oas.annotations.media.Schema;

public record RoleResponse(
        @Schema(description = "역할 ID") Long id,
        @Schema(description = "역할명") String name,
        @Schema(description = "역할 코드") String code
) {
    public static RoleResponse from(Role role) {
        return new RoleResponse(role.getId(), role.getName(), role.getCode());
    }
}
