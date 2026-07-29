package com.opensource.docgrid.domain.embedding.controller;

import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.embedding.dto.request.StartEmbeddingJobAttemptRequest;
import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentChunksRequest;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentChunksResponse;
import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.document.service.DocumentParsingService;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.ChunkResult;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService.StartResult;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ErrorResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * 관리자용 Embedding Job Claim과 Attempt 시작 요청을 HTTP API로 제공하는 Controller.
 *
 * <p>HTTP 입력 검증과 성공 상태 변환만 담당한다. Job Claim 및 현재 소유권 기반 Attempt 시작의
 * Transaction·동시성 규칙은 각 Command Service에 위임한다.
 */
@Tag(name = "Admin - Indexing Job", description = "관리자 전용 인덱싱 Job 제어 API")
@Validated
@RestController
@RequestMapping("/admin/indexing-jobs")
@RequiredArgsConstructor
public class IndexingJobAdminController {

    private final EmbeddingJobClaimService embeddingJobClaimService;
    private final EmbeddingJobAttemptService embeddingJobAttemptService;
    private final DocumentParsingService documentParsingService;

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

    @Operation(
        summary = "Embedding Job Attempt 시작",
        description = "현재 PROCESSING Job의 Worker ID와 Claim Token 및 유효한 Lease를 검증한 뒤 "
            + "실행 Attempt를 시작합니다. 최초 생성은 201, 같은 현재 Claim 재전송은 200을 반환합니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Attempt 최초 생성"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "같은 현재 Claim의 기존 Attempt 반환"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Job ID, Worker ID 또는 Claim Token 형식 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
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
            description = "Job 상태, 현재 소유권 또는 Lease 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "PROCESSING Job의 소유권 데이터 불일치",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PostMapping(
        value = "/{jobId}/attempts",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<StartedEmbeddingJobAttemptResponse>> startAttempt(
        @PathVariable @Positive Long jobId,
        @Valid @RequestBody StartEmbeddingJobAttemptRequest request
    ) {
        // 1. Service가 Job 잠금부터 소유권 검증, 멱등 조회와 Insert까지 하나의 Transaction으로 처리한다.
        StartResult result = embeddingJobAttemptService.start(jobId, request);

        // 2. 최초 생성과 멱등 재생은 같은 Body를 사용하고 HTTP 상태로만 구분한다.
        if (result.created()) {
            return ResponseUtils.created(result.response());
        }
        return ResponseUtils.ok(result.response());
    }

    @Operation(
        summary = "Document Chunk 생성",
        description = "현재 PROCESSING Job의 유효한 Attempt 소유권을 검증하고 TXT·Markdown 원본을 "
            + "Transaction 밖에서 읽고 파싱한 뒤 결정적인 Chunk Set으로 저장합니다. "
            + "최초 저장은 201, 기존 완료 결과의 멱등 재생은 200을 반환합니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Document Chunk 최초 저장"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "기존 CHUNKED 결과 재생"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Job ID, Attempt ID, Worker ID 또는 Claim Token 형식 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "인증되지 않았거나 ADMIN 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Embedding Job 또는 MinIO Object 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "현재 소유권, Attempt, Lease 또는 Version 상태 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "지원하지 않는 문서 형식, 빈 문서 또는 UTF-8 Decode 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "파일 연관, 소유권 또는 Chunk 데이터 불일치",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "MinIO 저장소 읽기 장애",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PostMapping(
        value = "/{jobId}/attempts/{attemptId}/chunks",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<DocumentChunksResponse>> createChunks(
        @PathVariable @Positive Long jobId,
        @PathVariable @Positive Long attemptId,
        @Valid @RequestBody CreateDocumentChunksRequest request
    ) {
        // 1. 비 Transaction Service가 준비·외부 작업·완료 Transaction의 순서를 조정한다.
        ChunkResult result = documentParsingService.createChunks(jobId, attemptId, request);

        // 2. 같은 응답 Body를 사용하고 실제 최초 저장 여부로 HTTP 상태만 구분한다.
        if (result.created()) {
            return ResponseUtils.created(result.response());
        }
        return ResponseUtils.ok(result.response());
    }
}
