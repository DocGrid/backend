package com.opensource.docgrid.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record AssignRoleRequest(
        @Schema(description = "부여할 역할 코드 (USER / ADMIN / DOCUMENT_MANAGER)") @NotBlank String roleCode
) {
}
