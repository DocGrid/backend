package com.opensource.docgrid.domain.worker.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.worker.dto.response.WorkerNodeResponse;
import com.opensource.docgrid.domain.worker.service.query.WorkerNodeQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ErrorResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin - Worker", description = "관리자 전용 인덱싱 Worker 상태 조회 API")
@RestController
@RequestMapping("/admin/workers")
@RequiredArgsConstructor
public class WorkerAdminController {

    private final WorkerNodeQueryService workerNodeQueryService;

    @Operation(
        summary = "Worker 목록 조회",
        description = "등록된 인덱싱 Worker를 최근 시작 순서로 조회합니다. 오래된 Heartbeat는 DEAD 상태로 계산합니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Worker 목록 조회 성공"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "인증되지 않았거나 ADMIN 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<List<WorkerNodeResponse>>> getWorkers() {
        return ResponseUtils.ok(workerNodeQueryService.getWorkers());
    }
}
