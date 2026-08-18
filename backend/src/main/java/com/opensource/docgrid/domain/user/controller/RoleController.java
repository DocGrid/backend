package com.opensource.docgrid.domain.user.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.user.dto.response.RoleResponse;
import com.opensource.docgrid.domain.user.service.query.RoleQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 역할 목록 조회 API.
 *
 * <p>권한 부여 대상(ROLE) 선택 등 프론트에서 역할 이름이 필요한 화면에 쓰인다.
 * 실제 조회·변환은 {@link RoleQueryService}에 위임한다.
 */
@Tag(name = "Role", description = "역할 관련 API")
@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleQueryService roleQueryService;

    @Operation(summary = "역할 목록 조회", description = "권한 부여 대상 선택 등에 쓰는 전체 역할 목록을 반환합니다.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<RoleResponse>>> getRoles() {
        return ResponseUtils.ok(roleQueryService.getRoles());
    }
}
