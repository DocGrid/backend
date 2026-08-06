package com.opensource.docgrid.domain.worker.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.worker.dto.response.WorkerNodeResponse;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.fixture.WorkerNodeFixture;
import com.opensource.docgrid.domain.worker.service.query.WorkerNodeQueryService;
import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;
import com.opensource.docgrid.global.config.SecurityConfig;

@WebMvcTest(WorkerAdminController.class)
@Import(SecurityConfig.class)
@DisplayName("WorkerAdminController 테스트")
class WorkerAdminControllerTest {

    private static final String WORKERS_URL = "/admin/workers";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkerNodeQueryService workerNodeQueryService;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private McpAccessTokenCommandService mcpAccessTokenCommandService;

    @MockitoBean
    private CorsConfigurationSource corsConfigurationSource;

    @Test
    @DisplayName("ADMIN 사용자가 Worker 목록을 조회한다")
    void getWorkers_returnsWorkers_when_userIsAdmin() throws Exception {
        WorkerNodeResponse response = new WorkerNodeResponse(
            WorkerNodeFixture.WORKER_ID,
            WorkerNodeFixture.WORKER_NAME,
            WorkerNodeFixture.INSTANCE_ID,
            WorkerNodeFixture.HOST_NAME,
            WorkerNodeFixture.IP_ADDRESS,
            WorkerStatus.ACTIVE,
            LocalDateTime.of(2026, 7, 20, 15, 0),
            WorkerNodeFixture.STARTED_AT,
            null
        );
        given(workerNodeQueryService.getWorkers()).willReturn(List.of(response));

        mockMvc.perform(get(WORKERS_URL).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].workerId").value(WorkerNodeFixture.WORKER_ID))
            .andExpect(jsonPath("$.data[0].workerName").value(WorkerNodeFixture.WORKER_NAME))
            .andExpect(jsonPath("$.data[0].instanceId").value(WorkerNodeFixture.INSTANCE_ID))
            .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("일반 사용자는 Worker 목록을 조회할 수 없다")
    void getWorkers_returnsForbidden_when_userIsNotAdmin() throws Exception {
        mockMvc.perform(get(WORKERS_URL).with(user("user").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인증되지 않은 사용자는 403으로 Worker 목록 조회가 거부된다")
    void getWorkers_returnsForbidden_when_userIsNotAuthenticated() throws Exception {
        mockMvc.perform(get(WORKERS_URL))
            .andExpect(status().isForbidden());
    }
}
