package com.opensource.docgrid.domain.user.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.user.dto.response.DepartmentResponse;
import com.opensource.docgrid.domain.user.service.query.DepartmentQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Department", description = "부서 관련 API")
@RestController
@RequestMapping("/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentQueryService departmentQueryService;

    @Operation(summary = "부서 목록 조회", description = "회원가입 화면 드롭다운에 표시할 ACTIVE 상태 부서 목록을 반환합니다. 인증 없이 호출 가능합니다.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<DepartmentResponse>>> getDepartments() {
        return ResponseUtils.ok(departmentQueryService.getActiveDepartments());
    }
}
