package com.opensource.docgrid.domain.dashboard.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.dashboard.dto.response.RetryAllJobsResponse;
import com.opensource.docgrid.domain.dashboard.service.command.EmbeddingJobRetryService;
import com.opensource.docgrid.domain.embedding.dto.response.ManualRetriedIndexingJobResponse;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ErrorResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 FAILED Embedding Job 재처리 트리거의 HTTP 경계.
 *
 * <p>요청 검증과 응답 변환만 담당하며, 실제 재처리 위임과 대시보드 push는
 * {@link EmbeddingJobRetryService}에 위임한다.
 */
@Tag(name = "Admin - Dashboard", description = "관리자 전용 RAGOps Dashboard 집계 지표 API")
@Validated
@RestController
@RequestMapping("/admin/embedding-jobs")
@RequiredArgsConstructor
public class EmbeddingJobRetryController {

    private final EmbeddingJobRetryService embeddingJobRetryService;

    @Operation(
        summary = "FAILED Job 단건 재처리",
        description = "최종 실패한 Embedding Job 하나를 다시 Queue에 넣습니다. "
            + "재처리 대상이 아니면(FAILED가 아니거나 대상 조건 불충족) 409, 존재하지 않으면 404를 반환합니다. "
            + "재처리 성공 시 최신 대시보드 집계를 WebSocket(/topic/dashboard)으로 즉시 push합니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "재처리 성공"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "인증되지 않았거나 ADMIN 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Embedding Job 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "FAILED 상태가 아니거나 재처리 대상 조건 불충족",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PostMapping(value = "/{jobId}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ManualRetriedIndexingJobResponse>> retryJob(
        @PathVariable @Positive Long jobId
    ) {
        return ResponseUtils.ok(embeddingJobRetryService.retryJob(jobId));
    }

    @Operation(
        summary = "FAILED Job 전체 재처리",
        description = "FAILED 상태인 모든 Embedding Job을 순차적으로 재처리합니다. "
            + "개별 Job 재처리가 실패해도 나머지는 계속 진행합니다. "
            + "현재 상태상 재처리할 수 없는 대상과 예상 밖 오류 건수를 분리해 반환합니다. "
            + "FAILED 작업이 없으면 모든 건수 0으로 정상 응답합니다. "
            + "1건 이상 성공하면 최신 대시보드 집계를 WebSocket으로 즉시 push합니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "전체 재처리 요청 처리 완료"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "인증되지 않았거나 ADMIN 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PostMapping(value = "/retry-all", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<RetryAllJobsResponse>> retryAllJobs() {
        return ResponseUtils.ok(embeddingJobRetryService.retryAllFailedJobs());
    }
}
