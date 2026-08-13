package com.opensource.docgrid.domain.user.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.opensource.docgrid.domain.user.enums.UserStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 사용자 목록에 필요한 계정·부서·역할 Metadata만 노출하는 응답이다.
 * 비밀번호 해시와 인증 관련 내부 정보는 이 계약의 범위에 포함하지 않는다.
 */
@Schema(description = "관리자 사용자 목록 항목")
public record AdminUserResponse(
    @Schema(description = "사용자 ID") Long userId,
    @Schema(description = "이름") String name,
    @Schema(description = "이메일") String email,
    @Schema(description = "닉네임") String nickname,
    @Schema(description = "부서 ID") Long departmentId,
    @Schema(description = "부서명") String departmentName,
    @Schema(description = "계정 상태") UserStatus status,
    @Schema(description = "부여된 역할 코드 목록") List<String> roles,
    @Schema(description = "마지막 로그인 시각") LocalDateTime lastLoginAt,
    @Schema(description = "가입 시각") LocalDateTime createdAt
) {
}
