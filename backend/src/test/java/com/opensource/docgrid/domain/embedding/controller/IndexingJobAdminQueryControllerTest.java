package com.opensource.docgrid.domain.embedding.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
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
import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.service.DocumentParsingService;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingEventResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingService;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingCompletionService;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingFailureService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobManualRetryService;
import com.opensource.docgrid.domain.embedding.service.query.IndexingJobAdminQueryService;
import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.config.SecurityConfig;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 관리자 인덱싱 Job 조회 API의 Pagination, 민감 정보 비노출, Validation과 Security 계약을 검증한다.
 */
@WebMvcTest(IndexingJobAdminController.class)
@Import(SecurityConfig.class)
@DisplayName("IndexingJobAdminController 조회 테스트")
class IndexingJobAdminQueryControllerTest {

    private static final String JOBS_URL = "/admin/indexing-jobs";
    private static final String JOB_URL = "/admin/indexing-jobs/10";
    private static final String ATTEMPTS_URL = "/admin/indexing-jobs/10/attempts";
    private static final String EVENTS_URL = "/admin/indexing-jobs/10/events";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private IndexingJobAdminQueryService indexingJobAdminQueryService;
    @MockitoBean private EmbeddingJobClaimService embeddingJobClaimService;
    @MockitoBean private EmbeddingJobLeaseService embeddingJobLeaseService;
    @MockitoBean private EmbeddingJobAttemptService embeddingJobAttemptService;
    @MockitoBean private DocumentParsingService documentParsingService;
    @MockitoBean private DocumentEmbeddingService documentEmbeddingService;
    @MockitoBean private DocumentIndexingCompletionService documentIndexingCompletionService;
    @MockitoBean private DocumentIndexingFailureService documentIndexingFailureService;
    @MockitoBean private EmbeddingJobManualRetryService embeddingJobManualRetryService;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private TokenBlacklistService tokenBlacklistService;
    @MockitoBean private McpAccessTokenCommandService mcpAccessTokenCommandService;
    @MockitoBean private CorsConfigurationSource corsConfigurationSource;

    @Test
    @DisplayName("ADMIN 사용자가 필터링한 Job 목록을 페이지 조회한다")
    void getJobs_returnsFilteredPage_withoutSensitiveFields() throws Exception {
        given(indexingJobAdminQueryService.getJobs(EmbeddingJobStatus.FAILED, 3L, 7L, 0, 20))
            .willReturn(new PageResponse<>(List.of(createJobResponse()), 0, 20, 1, 1, true, true));

        mockMvc.perform(get(JOBS_URL)
                .param("status", "FAILED")
                .param("documentId", "3")
                .param("workerId", "7")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].jobId").value(10))
            .andExpect(jsonPath("$.data.content[0].status").value("FAILED"))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].claimToken").doesNotExist())
            .andExpect(jsonPath("$.data.content[0].errorMessage").doesNotExist());
    }

    @Test
    @DisplayName("ADMIN 사용자가 Job 상세를 조회한다")
    void getJob_returnsSafeDetail() throws Exception {
        given(indexingJobAdminQueryService.getJob(10L)).willReturn(createJobResponse());

        mockMvc.perform(get(JOB_URL).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.documentId").value(3))
            .andExpect(jsonPath("$.data.embeddingModelName").value("BAAI/bge-m3"))
            .andExpect(jsonPath("$.data.claimToken").doesNotExist())
            .andExpect(jsonPath("$.data.errorMessage").doesNotExist());
    }

    @Test
    @DisplayName("Attempt 이력은 Claim Token과 내부 오류 메시지를 노출하지 않는다")
    void getAttempts_returnsSafeHistory() throws Exception {
        AdminIndexingJobAttemptResponse attempt = new AdminIndexingJobAttemptResponse(
            21L,
            2,
            AttemptStatus.FAILED,
            7L,
            "indexing-worker",
            LocalDateTime.of(2026, 8, 8, 10, 0),
            LocalDateTime.of(2026, 8, 8, 10, 1),
            60_000L,
            "EMBEDDING-PROVIDER-001"
        );
        given(indexingJobAdminQueryService.getAttempts(10L, 0, 20))
            .willReturn(new PageResponse<>(List.of(attempt), 0, 20, 1, 1, true, true));

        mockMvc.perform(get(ATTEMPTS_URL).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].attemptNo").value(2))
            .andExpect(jsonPath("$.data.content[0].claimToken").doesNotExist())
            .andExpect(jsonPath("$.data.content[0].errorMessage").doesNotExist());
    }

    @Test
    @DisplayName("Event 타임라인은 내부 Metadata JSON을 노출하지 않는다")
    void getEvents_returnsSafeTimeline() throws Exception {
        AdminIndexingEventResponse event = new AdminIndexingEventResponse(
            31L,
            IndexingEventType.RETRY,
            "PROCESSING",
            "PENDING",
            "인덱싱 Job 자동 재시도를 예약했습니다.",
            LocalDateTime.of(2026, 8, 8, 10, 1)
        );
        given(indexingJobAdminQueryService.getEvents(10L, 0, 20))
            .willReturn(new PageResponse<>(List.of(event), 0, 20, 1, 1, true, true));

        mockMvc.perform(get(EVENTS_URL).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].eventType").value("RETRY"))
            .andExpect(jsonPath("$.data.content[0].metadataJson").doesNotExist());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidQueryRequests")
    @DisplayName("조회 입력이 유효하지 않으면 400을 반환한다")
    void getQueries_returnBadRequest_whenInputIsInvalid(String description, String url) throws Exception {
        mockMvc.perform(get(url).with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    @DisplayName("존재하지 않는 Job 상세는 404를 반환한다")
    void getJob_returnsNotFound_whenJobDoesNotExist() throws Exception {
        given(indexingJobAdminQueryService.getJob(10L))
            .willThrow(new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));

        mockMvc.perform(get(JOB_URL).with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("EMBEDDING-JOB-001"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryUrls")
    @DisplayName("ADMIN이 아닌 사용자는 관리자 조회 API에 접근할 수 없다")
    void getQueries_returnForbidden_withoutAdminRole(String description, String url) throws Exception {
        mockMvc.perform(get(url).with(user("user").roles("USER")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(url)).andExpect(status().isForbidden());
    }

    private static Stream<Arguments> invalidQueryRequests() {
        return Stream.of(
            Arguments.of("문서 ID가 0", JOBS_URL + "?documentId=0"),
            Arguments.of("Worker ID가 음수", JOBS_URL + "?workerId=-1"),
            Arguments.of("Page가 음수", JOBS_URL + "?page=-1"),
            Arguments.of("Size가 0", JOBS_URL + "?size=0"),
            Arguments.of("Size가 100 초과", JOBS_URL + "?size=101"),
            Arguments.of("Job ID가 0", "/admin/indexing-jobs/0"),
            Arguments.of("Attempt Page가 음수", ATTEMPTS_URL + "?page=-1"),
            Arguments.of("Event Size가 100 초과", EVENTS_URL + "?size=101")
        );
    }

    private static Stream<Arguments> queryUrls() {
        return Stream.of(
            Arguments.of("목록", JOBS_URL),
            Arguments.of("상세", JOB_URL),
            Arguments.of("Attempt", ATTEMPTS_URL),
            Arguments.of("Event", EVENTS_URL)
        );
    }

    private AdminIndexingJobResponse createJobResponse() {
        return new AdminIndexingJobResponse(
            10L,
            EmbeddingJobStatus.FAILED,
            0,
            3,
            3,
            null,
            3L,
            "운영 가이드",
            5L,
            2,
            DocumentVersionStatus.FAILED,
            1L,
            "BAAI/bge-m3",
            "1",
            7L,
            "indexing-worker",
            "EMBEDDING-PROVIDER-001",
            LocalDateTime.of(2026, 8, 8, 10, 0),
            LocalDateTime.of(2026, 8, 8, 10, 5),
            LocalDateTime.of(2026, 8, 8, 9, 59),
            LocalDateTime.of(2026, 8, 8, 10, 0),
            null,
            LocalDateTime.of(2026, 8, 8, 10, 1)
        );
    }
}
