package com.opensource.docgrid.domain.embedding.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.service.DocumentParsingService;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.ChunkResult;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentChunksResponse;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentEmbeddingsResponse;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingCompletionResponse;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingFailureResponse;
import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingService;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingService.EmbeddingResult;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingCompletionService;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingFailureService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService.StartResult;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.global.config.SecurityConfig;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 관리자용 Job Claim, Attempt 시작과 Document Chunk·Embedding 생성·인덱싱 완료·실패 API 계약을 검증한다.
 *
 * <p>각 API의 최초 생성·멱등 재생·Validation·비즈니스 오류 및 ADMIN Security 동작을
 * 실제 Service 실행 없이 Controller 경계에서 확인한다.
 */
@WebMvcTest(IndexingJobAdminController.class)
@Import(SecurityConfig.class)
@DisplayName("IndexingJobAdminController 테스트")
class IndexingJobAdminControllerTest {

    private static final String CLAIM_URL = "/admin/indexing-jobs/claim";
    private static final String ATTEMPT_URL = "/admin/indexing-jobs/10/attempts";
    private static final String CHUNKS_URL = "/admin/indexing-jobs/10/attempts/100/chunks";
    private static final String EMBEDDINGS_URL = "/admin/indexing-jobs/10/attempts/100/embeddings";
    private static final String COMPLETE_URL = "/admin/indexing-jobs/10/attempts/100/complete";
    private static final String FAIL_URL = "/admin/indexing-jobs/10/attempts/100/fail";
    private static final Long JOB_ID = 10L;
    private static final Long ATTEMPT_ID = 100L;
    private static final Long WORKER_ID = 1L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String VALID_ATTEMPT_BODY = """
        {
          "workerId": 1,
          "claimToken": "34c19d16-6ae1-4f6a-a35d-0123456789ab"
        }
        """;
    private static final String VALID_FAILURE_BODY = """
        {
          "workerId": 1,
          "claimToken": "34c19d16-6ae1-4f6a-a35d-0123456789ab",
          "failureType": "EMBEDDING_PROVIDER_UNAVAILABLE",
          "errorMessage": "Embedding provider request timed out"
        }
        """;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private EmbeddingJobClaimService embeddingJobClaimService;
    @MockitoBean private EmbeddingJobAttemptService embeddingJobAttemptService;
    @MockitoBean private DocumentParsingService documentParsingService;
    @MockitoBean private DocumentEmbeddingService documentEmbeddingService;
    @MockitoBean private DocumentIndexingCompletionService documentIndexingCompletionService;
    @MockitoBean private DocumentIndexingFailureService documentIndexingFailureService;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private CorsConfigurationSource corsConfigurationSource;

    @Test
    @DisplayName("ADMIN 사용자가 PENDING Job을 Claim한다")
    void claim_returnsClaimedJob_when_userIsAdmin() throws Exception {
        LocalDateTime lockedAt = LocalDateTime.of(2026, 7, 22, 15, 0);
        ClaimedEmbeddingJobResponse response = new ClaimedEmbeddingJobResponse(
            10L,
            EmbeddingJobStatus.PROCESSING,
            WORKER_ID,
            5L,
            2L,
            "34c19d16-6ae1-4f6a-a35d-0123456789ab",
            lockedAt,
            lockedAt.plusMinutes(5)
        );
        given(embeddingJobClaimService.claim(WORKER_ID)).willReturn(Optional.of(response));

        mockMvc.perform(post(CLAIM_URL)
                .param("workerId", WORKER_ID.toString())
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.jobId").value(10))
            .andExpect(jsonPath("$.data.status").value("PROCESSING"))
            .andExpect(jsonPath("$.data.workerId").value(WORKER_ID))
            .andExpect(jsonPath("$.data.claimToken").value(response.claimToken()))
            .andExpect(jsonPath("$.data.lockExpiresAt").value("2026-07-22T15:05:00"));
    }

    @Test
    @DisplayName("Claim할 Job이 없으면 204를 반환한다")
    void claim_returnsNoContent_when_pendingJobDoesNotExist() throws Exception {
        given(embeddingJobClaimService.claim(WORKER_ID)).willReturn(Optional.empty());

        mockMvc.perform(post(CLAIM_URL)
                .param("workerId", WORKER_ID.toString())
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
    }

    @Test
    @DisplayName("존재하지 않는 Worker로 Claim하면 404를 반환한다")
    void claim_returnsNotFound_when_workerDoesNotExist() throws Exception {
        given(embeddingJobClaimService.claim(WORKER_ID))
            .willThrow(new DocGridException(ErrorCode.WORKER_NOT_FOUND));

        mockMvc.perform(post(CLAIM_URL)
                .param("workerId", WORKER_ID.toString())
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("WORKER-001"));
    }

    @Test
    @DisplayName("Claim할 수 없는 Worker이면 409를 반환한다")
    void claim_returnsConflict_when_workerIsNotAvailable() throws Exception {
        given(embeddingJobClaimService.claim(WORKER_ID))
            .willThrow(new DocGridException(ErrorCode.WORKER_NOT_AVAILABLE));

        mockMvc.perform(post(CLAIM_URL)
                .param("workerId", WORKER_ID.toString())
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WORKER-002"));
    }

    @Test
    @DisplayName("일반 사용자는 Job을 Claim할 수 없다")
    void claim_returnsForbidden_when_userIsNotAdmin() throws Exception {
        mockMvc.perform(post(CLAIM_URL)
                .param("workerId", WORKER_ID.toString())
                .with(user("user").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 사용자는 403으로 Job Claim이 거부된다")
    void claim_returnsForbidden_when_userIsNotAuthenticated() throws Exception {
        mockMvc.perform(post(CLAIM_URL).param("workerId", WORKER_ID.toString()))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 사용자의 최초 Attempt 시작은 201을 반환한다")
    void startAttempt_returnsCreated_when_attemptIsCreated() throws Exception {
        StartedEmbeddingJobAttemptResponse response = createAttemptResponse();
        given(embeddingJobAttemptService.start(eq(JOB_ID), any())).willReturn(new StartResult(response, true));

        mockMvc.perform(post(ATTEMPT_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.attemptId").value(100))
            .andExpect(jsonPath("$.data.jobId").value(JOB_ID))
            .andExpect(jsonPath("$.data.attemptNo").value(1))
            .andExpect(jsonPath("$.data.workerId").value(WORKER_ID))
            .andExpect(jsonPath("$.data.status").value("STARTED"))
            .andExpect(jsonPath("$.data.startedAt").value("2026-07-26T21:40:00"))
            .andExpect(jsonPath("$.data.claimToken").doesNotExist());
    }

    @Test
    @DisplayName("ADMIN 사용자의 같은 Claim 재전송은 기존 Attempt와 200을 반환한다")
    void startAttempt_returnsOk_when_attemptIsReplayed() throws Exception {
        StartedEmbeddingJobAttemptResponse response = createAttemptResponse();
        given(embeddingJobAttemptService.start(eq(JOB_ID), any())).willReturn(new StartResult(response, false));

        mockMvc.perform(post(ATTEMPT_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.attemptId").value(100))
            .andExpect(jsonPath("$.data.attemptNo").value(1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAttemptRequests")
    @DisplayName("Attempt 시작 입력 형식이 올바르지 않으면 400을 반환한다")
    void startAttempt_returnsBadRequest_when_requestIsInvalid(String description, String url, String body)
        throws Exception {
        mockMvc.perform(post(url)
                .contentType("application/json")
                .content(body)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("attemptBusinessErrors")
    @DisplayName("Attempt 시작 비즈니스 오류를 정의된 HTTP 상태와 코드로 반환한다")
    void startAttempt_returnsDefinedError(
        ErrorCode errorCode,
        int expectedStatus,
        String expectedCode
    ) throws Exception {
        given(embeddingJobAttemptService.start(eq(JOB_ID), any()))
            .willThrow(new DocGridException(errorCode));

        mockMvc.perform(post(ATTEMPT_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @Test
    @DisplayName("일반 사용자는 Attempt를 시작할 수 없다")
    void startAttempt_returnsForbidden_when_userIsNotAdmin() throws Exception {
        mockMvc.perform(post(ATTEMPT_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("user").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 사용자는 403으로 Attempt 시작이 거부된다")
    void startAttempt_returnsForbidden_when_userIsNotAuthenticated() throws Exception {
        mockMvc.perform(post(ATTEMPT_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 사용자의 최초 Chunk 저장은 201을 반환한다")
    void createChunks_returnsCreated_when_chunksAreCreated() throws Exception {
        DocumentChunksResponse response = createChunksResponse();
        given(documentParsingService.createChunks(eq(JOB_ID), eq(ATTEMPT_ID), any()))
            .willReturn(new ChunkResult(response, true));

        mockMvc.perform(post(CHUNKS_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.jobId").value(JOB_ID))
            .andExpect(jsonPath("$.data.attemptId").value(ATTEMPT_ID))
            .andExpect(jsonPath("$.data.documentVersionId").value(5))
            .andExpect(jsonPath("$.data.chunkCount").value(3))
            .andExpect(jsonPath("$.data.versionStatus").value("CHUNKED"))
            .andExpect(jsonPath("$.data.claimToken").doesNotExist());
    }

    @Test
    @DisplayName("ADMIN 사용자의 완료된 Chunk 재호출은 200을 반환한다")
    void createChunks_returnsOk_when_chunksAreReplayed() throws Exception {
        DocumentChunksResponse response = createChunksResponse();
        given(documentParsingService.createChunks(eq(JOB_ID), eq(ATTEMPT_ID), any()))
            .willReturn(new ChunkResult(response, false));

        mockMvc.perform(post(CHUNKS_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.chunkCount").value(3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidChunkRequests")
    @DisplayName("Chunk 생성 입력 형식이 올바르지 않으면 400을 반환한다")
    void createChunks_returnsBadRequest_when_requestIsInvalid(String description, String url, String body)
        throws Exception {
        mockMvc.perform(post(url)
                .contentType("application/json")
                .content(body)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chunkBusinessErrors")
    @DisplayName("Chunk 생성 비즈니스 오류를 정의된 HTTP 상태와 코드로 반환한다")
    void createChunks_returnsDefinedError(
        ErrorCode errorCode,
        int expectedStatus,
        String expectedCode
    ) throws Exception {
        given(documentParsingService.createChunks(eq(JOB_ID), eq(ATTEMPT_ID), any()))
            .willThrow(new DocGridException(errorCode));

        mockMvc.perform(post(CHUNKS_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @Test
    @DisplayName("일반 사용자와 미인증 사용자는 Chunk를 생성할 수 없다")
    void createChunks_returnsForbidden_withoutAdminRole() throws Exception {
        mockMvc.perform(post(CHUNKS_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("user").roles("USER")))
            .andExpect(status().isForbidden());

        mockMvc.perform(post(CHUNKS_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 사용자의 최초 Embedding 저장은 201을 반환한다")
    void createEmbeddings_returnsCreated_when_embeddingsAreCreated() throws Exception {
        DocumentEmbeddingsResponse response = createEmbeddingsResponse();
        given(documentEmbeddingService.createEmbeddings(eq(JOB_ID), eq(ATTEMPT_ID), any()))
            .willReturn(new EmbeddingResult(response, true));

        mockMvc.perform(post(EMBEDDINGS_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.jobId").value(JOB_ID))
            .andExpect(jsonPath("$.data.attemptId").value(ATTEMPT_ID))
            .andExpect(jsonPath("$.data.documentVersionId").value(5))
            .andExpect(jsonPath("$.data.embeddingModelId").value(7))
            .andExpect(jsonPath("$.data.chunkCount").value(3))
            .andExpect(jsonPath("$.data.embeddingCount").value(3))
            .andExpect(jsonPath("$.data.versionStatus").value("EMBEDDING"))
            .andExpect(jsonPath("$.data.claimToken").doesNotExist())
            .andExpect(jsonPath("$.data.vector").doesNotExist());
    }

    @Test
    @DisplayName("ADMIN 사용자의 완료된 Embedding 재호출은 200을 반환한다")
    void createEmbeddings_returnsOk_when_embeddingsAreReplayed() throws Exception {
        DocumentEmbeddingsResponse response = createEmbeddingsResponse();
        given(documentEmbeddingService.createEmbeddings(eq(JOB_ID), eq(ATTEMPT_ID), any()))
            .willReturn(new EmbeddingResult(response, false));

        mockMvc.perform(post(EMBEDDINGS_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.embeddingCount").value(3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEmbeddingRequests")
    @DisplayName("Embedding 생성 입력 형식이 올바르지 않으면 400을 반환한다")
    void createEmbeddings_returnsBadRequest_when_requestIsInvalid(
        String description,
        String url,
        String body
    ) throws Exception {
        mockMvc.perform(post(url)
                .contentType("application/json")
                .content(body)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("embeddingBusinessErrors")
    @DisplayName("Embedding 생성 비즈니스 오류를 정의된 HTTP 상태와 코드로 반환한다")
    void createEmbeddings_returnsDefinedError(
        ErrorCode errorCode,
        int expectedStatus,
        String expectedCode
    ) throws Exception {
        given(documentEmbeddingService.createEmbeddings(eq(JOB_ID), eq(ATTEMPT_ID), any()))
            .willThrow(new DocGridException(errorCode));

        mockMvc.perform(post(EMBEDDINGS_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @Test
    @DisplayName("일반 사용자와 미인증 사용자는 Embedding을 생성할 수 없다")
    void createEmbeddings_returnsForbidden_withoutAdminRole() throws Exception {
        mockMvc.perform(post(EMBEDDINGS_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("user").roles("USER")))
            .andExpect(status().isForbidden());

        mockMvc.perform(post(EMBEDDINGS_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 사용자의 최초 완료와 멱등 재생은 안정적인 응답으로 200을 반환한다")
    void completeIndexing_returnsOkWithoutSensitiveFields() throws Exception {
        DocumentIndexingCompletionResponse response = createCompletionResponse();
        given(documentIndexingCompletionService.complete(eq(JOB_ID), eq(ATTEMPT_ID), any()))
            .willReturn(response);

        mockMvc.perform(post(COMPLETE_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.jobId").value(JOB_ID))
            .andExpect(jsonPath("$.data.attemptId").value(ATTEMPT_ID))
            .andExpect(jsonPath("$.data.documentId").value(10))
            .andExpect(jsonPath("$.data.documentVersionId").value(22))
            .andExpect(jsonPath("$.data.embeddingModelId").value(1))
            .andExpect(jsonPath("$.data.jobStatus").value("INDEXED"))
            .andExpect(jsonPath("$.data.attemptStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.versionStatus").value("INDEXED"))
            .andExpect(jsonPath("$.data.completedAt").value("2026-07-31T16:00:00"))
            .andExpect(jsonPath("$.data.durationMs").value(8421))
            .andExpect(jsonPath("$.data.claimToken").doesNotExist())
            .andExpect(jsonPath("$.data.chunkText").doesNotExist())
            .andExpect(jsonPath("$.data.vector").doesNotExist());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCompletionRequests")
    @DisplayName("잘못된 인덱싱 완료 요청은 400을 반환한다")
    void completeIndexing_returnsBadRequest_when_requestIsInvalid(
        String description,
        String url,
        String body
    ) throws Exception {
        mockMvc.perform(post(url)
                .contentType("application/json")
                .content(body)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @MethodSource("completionBusinessErrors")
    @DisplayName("인덱싱 완료 비즈니스 오류를 정의된 HTTP 상태와 코드로 반환한다")
    void completeIndexing_returnsDefinedError(
        ErrorCode errorCode,
        int expectedStatus,
        String expectedCode
    ) throws Exception {
        given(documentIndexingCompletionService.complete(eq(JOB_ID), eq(ATTEMPT_ID), any()))
            .willThrow(new DocGridException(errorCode));

        mockMvc.perform(post(COMPLETE_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @Test
    @DisplayName("일반 사용자와 미인증 사용자는 인덱싱을 완료할 수 없다")
    void completeIndexing_returnsForbidden_withoutAdminRole() throws Exception {
        mockMvc.perform(post(COMPLETE_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY)
                .with(user("user").roles("USER")))
            .andExpect(status().isForbidden());

        mockMvc.perform(post(COMPLETE_URL)
                .contentType("application/json")
                .content(VALID_ATTEMPT_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 사용자의 최초 실패와 멱등 재생은 Attempt 기반 응답으로 200을 반환한다")
    void failIndexing_returnsOkWithoutSensitiveFields() throws Exception {
        DocumentIndexingFailureResponse response = createFailureResponse();
        given(documentIndexingFailureService.fail(eq(JOB_ID), eq(ATTEMPT_ID), any()))
            .willReturn(response);

        mockMvc.perform(post(FAIL_URL)
                .contentType("application/json")
                .content(VALID_FAILURE_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.jobId").value(JOB_ID))
            .andExpect(jsonPath("$.data.attemptId").value(ATTEMPT_ID))
            .andExpect(jsonPath("$.data.attemptNo").value(2))
            .andExpect(jsonPath("$.data.attemptStatus").value("FAILED"))
            .andExpect(jsonPath("$.data.failureType").value("EMBEDDING_PROVIDER_UNAVAILABLE"))
            .andExpect(jsonPath("$.data.failedAt").value("2026-08-03T10:30:00"))
            .andExpect(jsonPath("$.data.durationMs").value(42031))
            .andExpect(jsonPath("$.data.claimToken").doesNotExist())
            .andExpect(jsonPath("$.data.errorMessage").doesNotExist())
            .andExpect(jsonPath("$.data.jobStatus").doesNotExist())
            .andExpect(jsonPath("$.data.nextRetryAt").doesNotExist());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFailureRequests")
    @DisplayName("잘못된 인덱싱 실패 요청은 400을 반환한다")
    void failIndexing_returnsBadRequest_when_requestIsInvalid(
        String description,
        String url,
        String body
    ) throws Exception {
        mockMvc.perform(post(url)
                .contentType("application/json")
                .content(body)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @ParameterizedTest
    @MethodSource("failureBusinessErrors")
    @DisplayName("인덱싱 실패 비즈니스 오류를 정의된 HTTP 상태와 코드로 반환한다")
    void failIndexing_returnsDefinedError(
        ErrorCode errorCode,
        int expectedStatus,
        String expectedCode
    ) throws Exception {
        given(documentIndexingFailureService.fail(eq(JOB_ID), eq(ATTEMPT_ID), any()))
            .willThrow(new DocGridException(errorCode));

        mockMvc.perform(post(FAIL_URL)
                .contentType("application/json")
                .content(VALID_FAILURE_BODY)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @Test
    @DisplayName("일반 사용자와 미인증 사용자는 인덱싱 실패를 보고할 수 없다")
    void failIndexing_returnsForbidden_withoutAdminRole() throws Exception {
        mockMvc.perform(post(FAIL_URL)
                .contentType("application/json")
                .content(VALID_FAILURE_BODY)
                .with(user("user").roles("USER")))
            .andExpect(status().isForbidden());

        mockMvc.perform(post(FAIL_URL)
                .contentType("application/json")
                .content(VALID_FAILURE_BODY))
            .andExpect(status().isForbidden());
    }

    private static Stream<Arguments> invalidAttemptRequests() {
        return Stream.of(
            Arguments.of("Job ID가 양수가 아님", "/admin/indexing-jobs/0/attempts", VALID_ATTEMPT_BODY),
            Arguments.of("Worker ID가 양수가 아님", ATTEMPT_URL, """
                {"workerId": 0, "claimToken": "%s"}
                """.formatted(CLAIM_TOKEN)),
            Arguments.of("Claim Token 누락", ATTEMPT_URL, """
                {"workerId": 1}
                """),
            Arguments.of("Claim Token 공백", ATTEMPT_URL, """
                {"workerId": 1, "claimToken": " "}
                """),
            Arguments.of("Claim Token UUID 형식 오류", ATTEMPT_URL, """
                {"workerId": 1, "claimToken": "not-a-uuid"}
                """)
        );
    }

    private static Stream<Arguments> attemptBusinessErrors() {
        return Stream.of(
            Arguments.of(ErrorCode.EMBEDDING_JOB_NOT_FOUND, 404, "EMBEDDING-JOB-001"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_NOT_PROCESSING, 409, "EMBEDDING-JOB-002"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID, 409, "EMBEDDING-JOB-003"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_LEASE_EXPIRED, 409, "EMBEDDING-JOB-004"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT, 500, "EMBEDDING-JOB-005")
        );
    }

    private static Stream<Arguments> invalidChunkRequests() {
        return Stream.of(
            Arguments.of("Job ID가 양수가 아님", "/admin/indexing-jobs/0/attempts/100/chunks", VALID_ATTEMPT_BODY),
            Arguments.of("Attempt ID가 양수가 아님", "/admin/indexing-jobs/10/attempts/0/chunks", VALID_ATTEMPT_BODY),
            Arguments.of("Worker ID가 양수가 아님", CHUNKS_URL, """
                {"workerId": 0, "claimToken": "%s"}
                """.formatted(CLAIM_TOKEN)),
            Arguments.of("Claim Token 누락", CHUNKS_URL, """
                {"workerId": 1}
                """),
            Arguments.of("Claim Token 공백", CHUNKS_URL, """
                {"workerId": 1, "claimToken": " "}
                """),
            Arguments.of("Claim Token UUID 형식 오류", CHUNKS_URL, """
                {"workerId": 1, "claimToken": "not-a-uuid"}
                """)
        );
    }

    private static Stream<Arguments> chunkBusinessErrors() {
        return Stream.of(
            Arguments.of(ErrorCode.FILE_OBJECT_NOT_FOUND, 404, "DOCUMENT-STORAGE-002"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID, 409, "EMBEDDING-JOB-006"),
            Arguments.of(ErrorCode.DOCUMENT_VERSION_CHUNKING_NOT_ALLOWED, 409, "DOCUMENT-VERSION-005"),
            Arguments.of(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE, 422, "DOCUMENT-PARSING-001"),
            Arguments.of(ErrorCode.DOCUMENT_CONTENT_EMPTY, 422, "DOCUMENT-PARSING-002"),
            Arguments.of(ErrorCode.DOCUMENT_TEXT_DECODING_FAILED, 422, "DOCUMENT-PARSING-003"),
            Arguments.of(ErrorCode.DOCUMENT_FILE_REFERENCE_MISSING, 500, "DOCUMENT-PARSING-004"),
            Arguments.of(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT, 500, "DOCUMENT-CHUNK-001"),
            Arguments.of(ErrorCode.FILE_STORAGE_FAILED, 503, "DOCUMENT-STORAGE-001")
        );
    }

    private static Stream<Arguments> invalidEmbeddingRequests() {
        return Stream.of(
            Arguments.of(
                "Job ID가 양수가 아님",
                "/admin/indexing-jobs/0/attempts/100/embeddings",
                VALID_ATTEMPT_BODY
            ),
            Arguments.of(
                "Attempt ID가 양수가 아님",
                "/admin/indexing-jobs/10/attempts/0/embeddings",
                VALID_ATTEMPT_BODY
            ),
            Arguments.of("Worker ID가 양수가 아님", EMBEDDINGS_URL, """
                {"workerId": 0, "claimToken": "%s"}
                """.formatted(CLAIM_TOKEN)),
            Arguments.of("Claim Token UUID 형식 오류", EMBEDDINGS_URL, """
                {"workerId": 1, "claimToken": "not-a-uuid"}
                """)
        );
    }

    private static Stream<Arguments> embeddingBusinessErrors() {
        return Stream.of(
            Arguments.of(ErrorCode.EMBEDDING_JOB_NOT_FOUND, 404, "EMBEDDING-JOB-001"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID, 409, "EMBEDDING-JOB-006"),
            Arguments.of(ErrorCode.DOCUMENT_VERSION_EMBEDDING_NOT_ALLOWED, 409, "DOCUMENT-VERSION-006"),
            Arguments.of(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT, 500, "DOCUMENT-CHUNK-001"),
            Arguments.of(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT, 500, "DOCUMENT-EMBEDDING-001"),
            Arguments.of(ErrorCode.EMBEDDING_VECTOR_INVALID, 500, "DOCUMENT-EMBEDDING-002"),
            Arguments.of(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE, 503, "SEARCH-001")
        );
    }

    private static Stream<Arguments> invalidCompletionRequests() {
        return Stream.of(
            Arguments.of(
                "Job ID가 양수가 아님",
                "/admin/indexing-jobs/0/attempts/100/complete",
                VALID_ATTEMPT_BODY
            ),
            Arguments.of(
                "Attempt ID가 양수가 아님",
                "/admin/indexing-jobs/10/attempts/0/complete",
                VALID_ATTEMPT_BODY
            ),
            Arguments.of("Worker ID 누락", COMPLETE_URL, """
                {"claimToken": "%s"}
                """.formatted(CLAIM_TOKEN)),
            Arguments.of("Worker ID가 0", COMPLETE_URL, """
                {"workerId": 0, "claimToken": "%s"}
                """.formatted(CLAIM_TOKEN)),
            Arguments.of("Worker ID가 음수", COMPLETE_URL, """
                {"workerId": -1, "claimToken": "%s"}
                """.formatted(CLAIM_TOKEN)),
            Arguments.of("Claim Token 누락", COMPLETE_URL, """
                {"workerId": 1}
                """),
            Arguments.of("Claim Token 공백", COMPLETE_URL, """
                {"workerId": 1, "claimToken": " "}
                """),
            Arguments.of("Claim Token UUID 형식 오류", COMPLETE_URL, """
                {"workerId": 1, "claimToken": "not-a-uuid"}
                """)
        );
    }

    private static Stream<Arguments> completionBusinessErrors() {
        return Stream.of(
            Arguments.of(ErrorCode.EMBEDDING_JOB_NOT_FOUND, 404, "EMBEDDING-JOB-001"),
            Arguments.of(
                ErrorCode.DOCUMENT_INDEXING_COMPLETION_NOT_ALLOWED,
                409,
                "DOCUMENT-INDEXING-001"
            ),
            Arguments.of(
                ErrorCode.DOCUMENT_INDEXING_STALE_COMPLETION,
                409,
                "DOCUMENT-INDEXING-002"
            ),
            Arguments.of(
                ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT,
                500,
                "DOCUMENT-INDEXING-003"
            )
        );
    }

    private static Stream<Arguments> invalidFailureRequests() {
        return Stream.of(
            Arguments.of(
                "Job ID가 양수가 아님",
                "/admin/indexing-jobs/0/attempts/100/fail",
                VALID_FAILURE_BODY
            ),
            Arguments.of(
                "Attempt ID가 양수가 아님",
                "/admin/indexing-jobs/10/attempts/0/fail",
                VALID_FAILURE_BODY
            ),
            Arguments.of("Worker ID가 양수가 아님", FAIL_URL, """
                {
                  "workerId": 0,
                  "claimToken": "%s",
                  "failureType": "WORKER_INTERNAL_ERROR",
                  "errorMessage": "temporary failure"
                }
                """.formatted(CLAIM_TOKEN)),
            Arguments.of("Claim Token 형식 오류", FAIL_URL, """
                {
                  "workerId": 1,
                  "claimToken": "not-a-uuid",
                  "failureType": "WORKER_INTERNAL_ERROR",
                  "errorMessage": "temporary failure"
                }
                """),
            Arguments.of("실패 유형 누락", FAIL_URL, """
                {
                  "workerId": 1,
                  "claimToken": "%s",
                  "errorMessage": "temporary failure"
                }
                """.formatted(CLAIM_TOKEN)),
            Arguments.of("실패 유형 Enum 오류", FAIL_URL, """
                {
                  "workerId": 1,
                  "claimToken": "%s",
                  "failureType": "UNKNOWN_FAILURE",
                  "errorMessage": "temporary failure"
                }
                """.formatted(CLAIM_TOKEN)),
            Arguments.of("오류 메시지 공백", FAIL_URL, """
                {
                  "workerId": 1,
                  "claimToken": "%s",
                  "failureType": "WORKER_INTERNAL_ERROR",
                  "errorMessage": " "
                }
                """.formatted(CLAIM_TOKEN)),
            Arguments.of("오류 메시지 2000자 초과", FAIL_URL, """
                {
                  "workerId": 1,
                  "claimToken": "%s",
                  "failureType": "WORKER_INTERNAL_ERROR",
                  "errorMessage": "%s"
                }
                """.formatted(CLAIM_TOKEN, "x".repeat(2001)))
        );
    }

    private static Stream<Arguments> failureBusinessErrors() {
        return Stream.of(
            Arguments.of(ErrorCode.EMBEDDING_JOB_NOT_FOUND, 404, "EMBEDDING-JOB-001"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_NOT_PROCESSING, 409, "EMBEDDING-JOB-002"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID, 409, "EMBEDDING-JOB-003"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_LEASE_EXPIRED, 409, "EMBEDDING-JOB-004"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID, 409, "EMBEDDING-JOB-006"),
            Arguments.of(ErrorCode.EMBEDDING_JOB_FAILURE_CONFLICT, 409, "EMBEDDING-JOB-007"),
            Arguments.of(
                ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT,
                500,
                "DOCUMENT-INDEXING-004"
            )
        );
    }

    private StartedEmbeddingJobAttemptResponse createAttemptResponse() {
        return new StartedEmbeddingJobAttemptResponse(
            100L,
            JOB_ID,
            1,
            WORKER_ID,
            AttemptStatus.STARTED,
            LocalDateTime.of(2026, 7, 26, 21, 40)
        );
    }

    private DocumentChunksResponse createChunksResponse() {
        return new DocumentChunksResponse(
            JOB_ID,
            ATTEMPT_ID,
            5L,
            3,
            DocumentVersionStatus.CHUNKED
        );
    }

    private DocumentEmbeddingsResponse createEmbeddingsResponse() {
        return new DocumentEmbeddingsResponse(
            JOB_ID,
            ATTEMPT_ID,
            5L,
            7L,
            3,
            3,
            DocumentVersionStatus.EMBEDDING
        );
    }

    private DocumentIndexingCompletionResponse createCompletionResponse() {
        return new DocumentIndexingCompletionResponse(
            JOB_ID,
            ATTEMPT_ID,
            10L,
            22L,
            1L,
            EmbeddingJobStatus.INDEXED,
            AttemptStatus.SUCCESS,
            DocumentVersionStatus.INDEXED,
            LocalDateTime.of(2026, 7, 31, 16, 0),
            8_421L
        );
    }

    private DocumentIndexingFailureResponse createFailureResponse() {
        return new DocumentIndexingFailureResponse(
            JOB_ID,
            ATTEMPT_ID,
            2,
            AttemptStatus.FAILED,
            IndexingFailureType.EMBEDDING_PROVIDER_UNAVAILABLE,
            LocalDateTime.of(2026, 8, 3, 10, 30),
            42_031L
        );
    }
}
