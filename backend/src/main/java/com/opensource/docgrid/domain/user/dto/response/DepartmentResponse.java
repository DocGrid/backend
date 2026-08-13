package com.opensource.docgrid.domain.user.dto.response;

import com.opensource.docgrid.domain.user.entity.Department;

import io.swagger.v3.oas.annotations.media.Schema;

public record DepartmentResponse(
        @Schema(description = "부서 ID") Long id,
        @Schema(description = "부서명") String name,
        @Schema(description = "부서 코드") String code
) {
    public static DepartmentResponse from(Department department) {
        return new DepartmentResponse(department.getId(), department.getName(), department.getCode());
    }
}
