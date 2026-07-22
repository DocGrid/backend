package com.opensource.docgrid.domain.embedding.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.global.config.SecurityConfig;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 관리자용 Embedding Job Claim API의 HTTP 계약과 접근 권한을 검증하는 Web MVC 테스트.
 *
 * <p>Claim 성공·빈 Queue·Worker 오류 응답과 ADMIN, 일반 사용자, 미인증 사용자의 Security 동작을
 * Service 실행 없이 Controller 경계에서 확인한다.
 */
@WebMvcTest(IndexingJobAdminController.class)
@Import(SecurityConfig.class)
@DisplayName("IndexingJobAdminController 테스트")
class IndexingJobAdminControllerTest {

    private static final String CLAIM_URL = "/admin/indexing-jobs/claim";
    private static final Long WORKER_ID = 1L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private EmbeddingJobClaimService embeddingJobClaimService;
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
}
