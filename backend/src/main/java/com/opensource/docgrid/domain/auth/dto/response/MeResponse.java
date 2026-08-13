package com.opensource.docgrid.domain.auth.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.opensource.docgrid.domain.user.entity.User;

import io.swagger.v3.oas.annotations.media.Schema;

public record MeResponse(
        @Schema(description = "사용자 ID") Long userId,
        @Schema(description = "이메일") String email,
        @Schema(description = "이름") String name,
        @Schema(description = "닉네임") String nickname,
        @Schema(description = "프로필 이미지 URL") String profileImageUrl,
        @Schema(description = "부서 ID") Long departmentId,
        @Schema(description = "부서명") String departmentName,
        @Schema(description = "역할 목록") List<String> roles,
        @Schema(description = "계정 상태") String status,
        @Schema(description = "가입 시각") LocalDateTime createdAt,
        @Schema(description = "마지막 로그인 시각") LocalDateTime lastLoginAt
) {
    public static MeResponse of(User user, List<String> roles) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getNickname(),
                user.getProfileImageUrl(),
                user.getDepartment() != null ? user.getDepartment().getId() : null,
                user.getDepartment() != null ? user.getDepartment().getName() : null,
                roles,
                user.getStatus().name(),
                user.getCreatedAt(),
                user.getLastLoginAt()
        );
    }
}
