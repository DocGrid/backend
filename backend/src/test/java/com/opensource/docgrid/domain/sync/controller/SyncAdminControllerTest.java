package com.opensource.docgrid.domain.sync.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;
import com.opensource.docgrid.domain.sync.dto.response.SyncAdminSummaryResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncEventSummaryResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncIssueSummaryResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncReconciliationAdminResponse;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.service.command.SyncAdminCommandService;
import com.opensource.docgrid.domain.sync.service.query.SyncAdminQueryService;
import com.opensource.docgrid.global.config.SecurityConfig;

/**
 * Sync 관리자 조회·수동 Reconciliation API의 응답, Validation과 ADMIN 권한 경계를 검증한다.
 */
@WebMvcTest(SyncAdminController.class)
@Import(SecurityConfig.class)
@DisplayName("SyncAdminController 테스트")
class SyncAdminControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SyncAdminQueryService syncAdminQueryService;
    @MockitoBean private SyncAdminCommandService syncAdminCommandService;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private McpAccessTokenCommandService mcpAccessTokenCommandService;
    @MockitoBean private CorsConfigurationSource corsConfigurationSource;

    @Test
    @DisplayName("ADMIN 사용자는 Outbox 지연과 Issue 요약을 조회한다")
    void getSummary_returnsSyncSnapshotForAdmin() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 23, 0);
        given(syncAdminQueryService.getSummary()).willReturn(new SyncAdminSummaryResponse(
            now,
            new SyncEventSummaryResponse(4, 2, 1, 300L, 9, 1, 3, 90.0, UUID.randomUUID(), now),
            new SyncIssueSummaryResponse(2, 1, 5, 1),
            null
        ));

        mockMvc.perform(get("/admin/sync/summary").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.events.pendingCount").value(4))
            .andExpect(jsonPath("$.data.events.oldestPendingAgeSeconds").value(300))
            .andExpect(jsonPath("$.data.events.successRateLast24h").value(90.0))
            .andExpect(jsonPath("$.data.issues.openCount").value(2));
    }

    @Test
    @DisplayName("일반 사용자와 미인증 사용자는 Sync 운영 API에 접근할 수 없다")
    void getSummary_returnsForbiddenWithoutAdminRole() throws Exception {
        mockMvc.perform(get("/admin/sync/summary").with(user("user").roles("USER")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/sync/summary"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 수동 Reconciliation은 인증 사용자 ID와 요청 모드를 Service에 전달한다")
    void reconcile_runsAuditedRepairBatch() throws Exception {
        UUID runId = UUID.randomUUID();
        given(syncAdminCommandService.reconcile(0L, SyncReconciliationMode.REPAIR, 7L))
            .willReturn(new SyncReconciliationAdminResponse(runId, 0, 100, 100, 3, 2, false, null));

        mockMvc.perform(post("/admin/sync/reconcile")
                .with(authentication(adminAuthentication()))
                .contentType("application/json")
                .content("{\"mode\":\"REPAIR\",\"cursor\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runId").value(runId.toString()))
            .andExpect(jsonPath("$.data.detectedCount").value(3))
            .andExpect(jsonPath("$.data.repairRequestedCount").value(2));
        then(syncAdminCommandService).should().reconcile(0L, SyncReconciliationMode.REPAIR, 7L);
    }

    @Test
    @DisplayName("Issue 무시 사유가 비어 있으면 400을 반환한다")
    void ignoreIssue_rejectsBlankReason() throws Exception {
        mockMvc.perform(post("/admin/sync/issues/41/ignore")
                .with(authentication(adminAuthentication()))
                .contentType("application/json")
                .content("{\"reason\":\" \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    private UsernamePasswordAuthenticationToken adminAuthentication() {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            "admin@docgrid.io",
            null,
            java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        authentication.setDetails(7L);
        return authentication;
    }
}
