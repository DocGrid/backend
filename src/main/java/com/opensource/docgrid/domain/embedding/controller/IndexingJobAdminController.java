package com.opensource.docgrid.domain.embedding.controller;

import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ErrorResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 관리자용 Embedding Job Claim 요청을 HTTP API로 제공하는 Controller.
 *
 * <p>요청의 Worker ID를 Command Service에 전달하고, Claim 결과 유무를 200 또는 204 응답으로 변환한다.
 * Job 선택, Worker 생존 검증, Lease 생성 같은 비즈니스 규칙은 {@link EmbeddingJobClaimService}에 위임한다.
 */
@Tag(name = "Admin - Indexing Job", description = "관리자 전용 인덱싱 Job 제어 API")
@RestController
@RequestMapping("/admin/indexing-jobs")
@RequiredArgsConstructor
public class IndexingJobAdminController {

    private final EmbeddingJobClaimService embeddingJobClaimService;

    @Operation(
        summary = "PENDING Job Claim",
        description = "Heartbeat가 유효한 Worker에게 우선순위가 가장 높은 PENDING Job 하나의 Lease를 부여합니다. "
            + "처리할 Job이 없으면 204를 반환합니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Job Claim 성공"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204",
            description = "Claim할 PENDING Job 없음"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "인증되지 않았거나 ADMIN 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Worker 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Worker가 Claim할 수 없는 상태",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PostMapping(value = "/claim", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ClaimedEmbeddingJobResponse>> claim(
        @RequestParam Long workerId
    ) {
        // 1. Service가 Worker 검증부터 DB 행 잠금과 Lease 발급까지 하나의 Transaction으로 처리한다.
        Optional<ClaimedEmbeddingJobResponse> claimedJob = embeddingJobClaimService.claim(workerId);

        // 2. PENDING Job이 없거나 모든 후보가 잠겨 있으면 정상적인 빈 Queue 응답을 반환한다.
        if (claimedJob.isEmpty()) {
            return ResponseUtils.noContent();
        }

        // 3. Claim에 성공하면 Worker가 후속 처리에 사용할 소유권 정보와 Token을 반환한다.
        return ResponseUtils.ok(claimedJob.get());
    }
}
