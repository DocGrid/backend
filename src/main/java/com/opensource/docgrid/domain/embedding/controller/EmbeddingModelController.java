package com.opensource.docgrid.domain.embedding.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.embedding.dto.response.EmbeddingModelResponse;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ErrorResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(
    name = "Embedding Model",
    description = "문서 인덱싱과 검색에서 사용할 임베딩 모델 설정 조회 API"
)
@RestController
@RequestMapping("/api/embedding-models")
@RequiredArgsConstructor
public class EmbeddingModelController {

    private final EmbeddingModelQueryService embeddingModelQueryService;

    @Operation(
        summary = "기본 임베딩 모델 조회",
        description = """
            is_active=true, is_searchable=true인 기본 임베딩 모델을 조회합니다.
            신규 embedding_job 생성 시 사용할 모델 설정이며, 정상적으로 하나만 존재해야 합니다.
            모델이 없거나 여러 개 존재하면 서버 설정 오류가 발생합니다.
            실제 Vector를 생성하거나 임베딩 모델을 실행하지 않습니다.
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "기본 임베딩 모델 조회 성공"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "기본 임베딩 모델 설정 오류",
            content = @Content(
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(
                        name = "모델 미설정",
                        value = """
                            {"success":false,"status":500,"code":"EMBEDDING-MODEL-001","message":"사용 가능한 임베딩 모델이 설정되지 않았습니다.","method":"GET","path":"/api/embedding-models/active","timestamp":"2026-07-14 12:00:00"}
                            """
                    ),
                    @ExampleObject(
                        name = "모델 중복 설정",
                        value = """
                            {"success":false,"status":500,"code":"EMBEDDING-MODEL-002","message":"사용 가능한 임베딩 모델이 여러 개 설정되어 있습니다.","method":"GET","path":"/api/embedding-models/active","timestamp":"2026-07-14 12:00:00"}
                            """
                    )
                }
            )
        )
    })
    @GetMapping(value = "/active", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<EmbeddingModelResponse>> getActiveModel() {
        return ResponseUtils.ok(embeddingModelQueryService.getActiveModelResponse());
    }
}
