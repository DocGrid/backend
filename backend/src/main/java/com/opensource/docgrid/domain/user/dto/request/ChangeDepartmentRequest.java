package com.opensource.docgrid.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ChangeDepartmentRequest(
        @Schema(description = "변경할 부서 ID") @NotNull Long departmentId
) {
}
