package com.opensource.docgrid.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// 관리자가 대상 사용자의 소속 부서를 변경할 때 쓰는 요청 경계. departmentId만 검증하면 되므로 단일 필드로 둔다.
public record ChangeDepartmentRequest(
        @Schema(description = "변경할 부서 ID") @NotNull Long departmentId
) {
}
