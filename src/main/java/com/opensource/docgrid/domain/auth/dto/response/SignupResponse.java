package com.opensource.docgrid.domain.auth.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.opensource.docgrid.domain.user.entity.User;

import io.swagger.v3.oas.annotations.media.Schema;

public record SignupResponse(
        @Schema(description = "사용자 ID") Long userId,
        @Schema(description = "이메일") String email,
        @Schema(description = "이름") String name,
        @Schema(description = "부서 ID") Long departmentId,
        @Schema(description = "부여된 역할 목록") List<String> roles,
        @Schema(description = "가입 시각") LocalDateTime createdAt
) {
    public static SignupResponse of(User user, List<String> roles) {
        return new SignupResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getDepartment() != null ? user.getDepartment().getId() : null,
                roles,
                user.getCreatedAt()
        );
    }
}
