package com.opensource.docgrid.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @Schema(description = "이메일 (로그인 ID)") @NotBlank @Email String email,
        @Schema(description = "비밀번호 (12자 이상 64자 이하)") @NotBlank @Size(min = 12, max = 64) String password,
        @Schema(description = "이름") @NotBlank String name,
        @Schema(description = "부서 ID") @NotNull Long departmentId
) {
}
