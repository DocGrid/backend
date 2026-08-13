package com.opensource.docgrid.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SignupRequest(
        @Schema(description = "이메일 (로그인 ID)") @NotBlank @Email String email,
        @Schema(description = "비밀번호") @NotBlank String password,
        @Schema(description = "이름") @NotBlank String name,
        @Schema(description = "부서 ID") @NotNull Long departmentId
) {
}
