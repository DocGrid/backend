package com.opensource.docgrid.domain.user.dto.response;

import java.util.List;

import com.opensource.docgrid.domain.user.entity.User;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserRoleResponse(
        @Schema(description = "사용자 ID") Long userId,
        @Schema(description = "이메일") String email,
        @Schema(description = "이름") String name,
        @Schema(description = "역할 목록") List<String> roles
) {
    public static UserRoleResponse of(User user, List<String> roles) {
        return new UserRoleResponse(user.getId(), user.getEmail(), user.getName(), roles);
    }
}
