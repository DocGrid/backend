package com.opensource.docgrid.domain.embedding.controller;

import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.embedding.dto.request.CompleteDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentChunksRequest;
import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentEmbeddingsRequest;
import com.opensource.docgrid.domain.embedding.dto.request.FailDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.request.RenewEmbeddingJobLeaseRequest;
import com.opensource.docgrid.domain.embedding.dto.request.StartEmbeddingJobAttemptRequest;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingEventResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentChunksResponse;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentEmbeddingsResponse;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingCompletionResponse;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingFailureResponse;
import com.opensource.docgrid.domain.embedding.dto.response.ManualRetriedIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.dto.response.RenewedEmbeddingJobLeaseResponse;
import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.document.service.DocumentParsingService;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.ChunkResult;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingService;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingService.EmbeddingResult;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingCompletionService;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingFailureService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService.StartResult;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobManualRetryService;
import com.opensource.docgrid.domain.embedding.service.query.IndexingJobAdminQueryService;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ErrorResponse;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * 관리자용 Embedding Job 목록·상세·Attempt·Event 조회와 Claim·Lease 갱신, Attempt 시작 및 문서
 * Chunk·Embedding·인덱싱 완료·실패 실행과 최종 실패 Job 수동 재처리를 HTTP API로 제공한다.
 *
 * <p>HTTP 입력 검증과 성공 상태 변환만 담당한다. Job Claim 및 현재 소유권 기반 파이프라인 단계와
 * 수동 재처리의 Transaction·외부 호출·동시성 규칙은 각 Service에 위임한다.
 */
@Tag(name = "Admin - Indexing Job", description = "관리자 전용 인덱싱 Job 조회·제어 API")
@Validated
@RestController
@RequestMapping("/admin/indexing-jobs")
@RequiredArgsConstructor
public class IndexingJobAdminController {

    private final EmbeddingJobClaimService embeddingJobClaimService;
    private final EmbeddingJobLeaseService embeddingJobLeaseService;
    private final EmbeddingJobAttemptService embeddingJobAttemptService;
    private final DocumentParsingService documentParsingService;
    private final DocumentEmbeddingService documentEmbeddingService;
    private final DocumentIndexingCompletionService documentIndexingCompletionService;
    private final DocumentIndexingFailureService documentIndexingFailureService;
    private final EmbeddingJobManualRetryService embeddingJobManualRetryService;
    private final IndexingJobAdminQueryService indexingJobAdminQueryService;

    @Operation(
        summary = "인덱싱 Job 목록 조회",
        description = "상태, 문서, 현재 소유 Worker 조건으로 인덱싱 Job을 필터링하고 최신 생성 순으로 "
            + "페이지 조회합니다. Claim Token과 내부 오류 메시지는 반환하지 않습니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "인덱싱 Job 목록 조회 성공"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "필터 또는 페이지 입력 형식 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "인증되지 않았거나 ADMIN 권한 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<PageResponse<AdminIndexingJobResponse>>> getJobs(
        @RequestParam(required = false) EmbeddingJobStatus status,
        @RequestParam(required = false) @Positive Long documentId,
        @RequestParam(required = false) @Positive Long workerId,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseUtils.ok(indexingJobAdminQueryService.getJobs(
            status,
            documentId,
            workerId,
            page,
            size
        ));
    }

    @Operation(
        summary = "인덱싱 Job 상세 조회",
        description = "지정한 Job의 문서·버전·모델·현재 Worker와 Retry·Lease·종결 상태를 조회합니다. "
            + "Claim Token과 내부 오류 메시지는 반환하지 않습니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "인덱싱 Job 상세 조회 성공"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Job ID 형식 오류",
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
        )
    })
    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<AdminIndexingJobResponse>> getJob(
        @PathVariable @Positive Long jobId
    ) {
        return ResponseUtils.ok(indexingJobAdminQueryService.getJob(jobId));
    }

    @Operation(
        summary = "인덱싱 Job Attempt 이력 조회",
        description = "지정한 Job의 실행 Attempt를 최근 시도 순으로 페이지 조회합니다. 과거 Claim Token과 "
            + "내부 오류 메시지는 반환하지 않습니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Attempt 이력 조회 성공"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Job ID 또는 페이지 입력 형식 오류",
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
        )
    })
    @GetMapping(value = "/{jobId}/attempts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<PageResponse<AdminIndexingJobAttemptResponse>>> getAttempts(
        @PathVariable @Positive Long jobId,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseUtils.ok(indexingJobAdminQueryService.getAttempts(jobId, page, size));
    }

    @Operation(
        summary = "인덱싱 Job Event 타임라인 조회",
        description = "지정한 Job의 상태 전이 Event를 최근 발생 순으로 페이지 조회합니다. 내부 Metadata "
            + "JSON은 반환하지 않습니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Event 타임라인 조회 성공"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Job ID 또는 페이지 입력 형식 오류",
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
        )
    })
    @GetMapping(value = "/{jobId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<PageResponse<AdminIndexingEventResponse>>> getEvents(
        @PathVariable @Positive Long jobId,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseUtils.ok(indexingJobAdminQueryService.getEvents(jobId, page, size));
    }

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
        summary = "PROCESSING Job Lease 갱신",
        description = "현재 Job의 Worker ID와 Claim Token 및 만료 전 Lease를 검증하고, "
            + "Heartbeat가 유효한 ACTIVE 또는 IDLE Worker의 Lease 만료 시각만 연장합니다. "
            + "Claim Token은 응답에 포함하지 않습니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Job Lease 갱신 성공"
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
            description = "Embedding Job 또는 Worker 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Job 상태, 현재 소유권, Lease 또는 Worker 생존 상태 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "PROCESSING Job의 소유권 데이터 불일치",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PostMapping(
        value = "/{jobId}/lease/renew",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<RenewedEmbeddingJobLeaseResponse>> renewLease(
        @PathVariable @Positive Long jobId,
        @Valid @RequestBody RenewEmbeddingJobLeaseRequest request
    ) {
        // Service가 Job → Worker 잠금과 소유권·생존 검증 및 Lease 갱신을 한 Transaction으로 처리한다.
        return ResponseUtils.ok(embeddingJobLeaseService.renew(jobId, request));
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
        description = "현재 PROCESSING Job의 유효한 Attempt 소유권을 검증하고 TXT·Markdown·PDF·DOCX 원본을 "
            + "설정된 파일 저장소에서 Transaction 밖으로 읽어 파싱한 뒤 결정적인 Chunk Set으로 저장합니다. "
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
            description = "Embedding Job 또는 원본 파일 Object 없음",
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
            description = "설정된 파일 저장소 읽기 장애",
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

    @Operation(
        summary = "Document Chunk Embedding 생성",
        description = "현재 PROCESSING Job의 유효한 Attempt 소유권과 Job 고정 Model을 검증하고 "
            + "Chunk를 순서대로 외부 Embedding 서버에 전달한 뒤 Vector Set을 원자 저장합니다. "
            + "최초 저장은 201, 기존 완료 결과의 멱등 재생은 200을 반환합니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Document Embedding 최초 저장"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "기존 Embedding 결과 재생"
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
            description = "Embedding Job 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "현재 소유권, Attempt, Lease 또는 Version 상태 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "Model, Chunk, Vector 또는 Embedding 저장 상태 불일치",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "외부 Embedding 서버 장애",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PostMapping(
        value = "/{jobId}/attempts/{attemptId}/embeddings",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<DocumentEmbeddingsResponse>> createEmbeddings(
        @PathVariable @Positive Long jobId,
        @PathVariable @Positive Long attemptId,
        @Valid @RequestBody CreateDocumentEmbeddingsRequest request
    ) {
        // 1. 비 Transaction Service가 준비·외부 호출·완료 Transaction의 순서를 조정한다.
        EmbeddingResult result = documentEmbeddingService.createEmbeddings(jobId, attemptId, request);

        // 2. 같은 응답 Body를 사용하고 실제 최초 저장 여부로 HTTP 상태만 구분한다.
        if (result.created()) {
            return ResponseUtils.created(result.response());
        }
        return ResponseUtils.ok(result.response());
    }

    @Operation(
        summary = "Document 인덱싱 완료",
        description = "현재 PROCESSING Job의 소유권과 Attempt, 최신 Version 및 전체 ACTIVE Embedding Set을 "
            + "검증한 뒤 Version과 Document를 검색 가능한 INDEXED 상태로 확정합니다. "
            + "같은 완료 실행의 재요청은 저장된 최초 결과를 멱등 재생합니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Document 인덱싱 최초 완료 또는 기존 완료 결과 재생"
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
            description = "Embedding Job 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "현재 소유권, Attempt, Lease 또는 최신 Version 상태 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "Model, Chunk, Embedding, 완료 시각 또는 이벤트 데이터 불일치",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PostMapping(
        value = "/{jobId}/attempts/{attemptId}/complete",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<DocumentIndexingCompletionResponse>> completeIndexing(
        @PathVariable @Positive Long jobId,
        @PathVariable @Positive Long attemptId,
        @Valid @RequestBody CompleteDocumentIndexingRequest request
    ) {
        // 최초 완료와 멱등 재생 모두 같은 안정적인 완료 응답을 200 OK로 반환한다.
        return ResponseUtils.ok(
            documentIndexingCompletionService.complete(jobId, attemptId, request)
        );
    }

    @Operation(
        summary = "Document 인덱싱 실패",
        description = "현재 PROCESSING Job의 소유권과 Attempt를 검증하고 서버 실패 유형 정책에 따라 "
            + "지연 Retry를 예약하거나 Version과 Job을 최종 실패로 종료합니다. "
            + "같은 실패 실행의 재요청은 저장된 최초 Attempt 결과를 멱등 재생합니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Document 인덱싱 최초 실패 기록 또는 기존 실패 결과 재생"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "ID, Worker, Claim Token, 실패 유형 또는 오류 메시지 형식 오류",
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
            description = "현재 소유권, Lease, Attempt 또는 기존 실패 내용 충돌",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "Version, Document 또는 실패 이력 데이터 불일치",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PostMapping(
        value = "/{jobId}/attempts/{attemptId}/fail",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<DocumentIndexingFailureResponse>> failIndexing(
        @PathVariable @Positive Long jobId,
        @PathVariable @Positive Long attemptId,
        @Valid @RequestBody FailDocumentIndexingRequest request
    ) {
        // 최초 실패와 멱등 재생 모두 같은 Attempt 기반 실패 응답을 200 OK로 반환한다.
        return ResponseUtils.ok(documentIndexingFailureService.fail(jobId, attemptId, request));
    }

    @Operation(
        summary = "최종 실패 Job 수동 재처리",
        description = "자동 재시도를 모두 마치고 최종 실패한 Job만 즉시 Claim 가능한 PENDING 상태로 되돌립니다. "
            + "처리 중이거나 자동 재시도가 예정된 Job과 이미 재처리된 Job의 중복 요청은 409로 거부합니다. "
            + "이미 저장된 Chunk가 있으면 파싱을 생략하고 임베딩 단계부터 다시 시작하며, "
            + "현재 검색 가능한 이전 Version과 기존 Attempt 이력, 재시도 횟수는 그대로 유지합니다. "
            + "재시도 횟수는 초기화하지 않으므로 이번 재처리가 다시 실패하면 곧바로 최종 실패로 종료됩니다."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "수동 재처리 성공"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Job ID 형식 오류",
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
            description = "최종 실패 Job이 아니거나 최신 Version·문서 상태가 재처리 조건을 만족하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "Version 또는 Document 종료 데이터 불일치",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    @PostMapping(value = "/{jobId}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ManualRetriedIndexingJobResponse>> retryIndexingJob(
        @PathVariable @Positive Long jobId
    ) {
        // Service가 Job → Version → Document 잠금과 대상 검증 및 Queue 복귀를 한 Transaction으로 처리한다.
        return ResponseUtils.ok(embeddingJobManualRetryService.retry(jobId));
    }
}
