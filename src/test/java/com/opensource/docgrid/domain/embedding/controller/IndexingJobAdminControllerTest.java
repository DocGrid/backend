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
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService.StartResult;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.global.config.SecurityConfig;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 관리자용 Embedding Job Claim 및 Attempt 시작 API의 HTTP 계약과 접근 권한을 검증하는 Web MVC 테스트.
 *
 * <p>Claim 응답과 Attempt 최초 생성·멱등 재생·Validation·비즈니스 오류 및 ADMIN Security 동작을
 * Service 실행 없이 Controller 경계에서 확인한다.
 */
@WebMvcTest(IndexingJobAdminController.class)
@Import(SecurityConfig.class)
@DisplayName("IndexingJobAdminController 테스트")
class IndexingJobAdminControllerTest {

    private static final String CLAIM_URL = "/admin/indexing-jobs/claim";
    private static final String ATTEMPT_URL = "/admin/indexing-jobs/10/attempts";
    private static final Long JOB_ID = 10L;
    private static final Long WORKER_ID = 1L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String VALID_ATTEMPT_BODY = """
        {
          "workerId": 1,
          "claimToken": "34c19d16-6ae1-4f6a-a35d-0123456789ab"
        }
        """;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private EmbeddingJobClaimService embeddingJobClaimService;
    @MockitoBean private EmbeddingJobAttemptService embeddingJobAttemptService;
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
}
