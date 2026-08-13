package com.opensource.docgrid.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Schema(description = "이메일") @NotBlank @Email String email,
        @Schema(description = "비밀번호") @NotBlank String password
) {
}
