package com.opensource.docgrid.domain.user.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;
import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;
import com.opensource.docgrid.domain.user.dto.request.ChangeDepartmentRequest;
import com.opensource.docgrid.domain.user.dto.response.AdminUserResponse;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.service.command.UserCommandService;
import com.opensource.docgrid.domain.user.service.command.UserRoleCommandService;
import com.opensource.docgrid.domain.user.service.query.AdminUserQueryService;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.config.SecurityConfig;

/**
 * 관리자 사용자 목록 API의 필터·Pagination·민감 정보 비노출과 ADMIN Security 계약을 검증한다.
 */
@WebMvcTest(AdminUserController.class)
@Import(SecurityConfig.class)
@DisplayName("AdminUserController 테스트")
class AdminUserControllerTest {

    private static final String USERS_URL = "/admin/users";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private UserRoleCommandService userRoleCommandService;
    @MockitoBean private UserCommandService userCommandService;
    @MockitoBean private AdminUserQueryService adminUserQueryService;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private TokenBlacklistService tokenBlacklistService;
    @MockitoBean private McpAccessTokenCommandService mcpAccessTokenCommandService;
    @MockitoBean private CorsConfigurationSource corsConfigurationSource;

    @Test
    @DisplayName("ADMIN 사용자가 검색·부서·상태 필터로 사용자와 역할을 페이지 조회한다")
    void getUsers_returnsFilteredUserPage() throws Exception {
        AdminUserResponse response = new AdminUserResponse(
                10L,
                "관리자",
                "admin@example.com",
                "admin",
                3L,
                "플랫폼팀",
                UserStatus.ACTIVE,
                List.of("ADMIN", "USER"),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0)
        );
        given(adminUserQueryService.getUsers("admin", 3L, UserStatus.ACTIVE, 0, 20))
                .willReturn(new PageResponse<>(List.of(response), 0, 20, 1, 1, true, true));

        mockMvc.perform(get(USERS_URL)
                        .param("keyword", "admin")
                        .param("departmentId", "3")
                        .param("status", "ACTIVE")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").value(10))
                .andExpect(jsonPath("$.data.content[0].email").value("admin@example.com"))
                .andExpect(jsonPath("$.data.content[0].departmentName").value("플랫폼팀"))
                .andExpect(jsonPath("$.data.content[0].roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.data.content[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("ADMIN이 아닌 사용자는 전체 사용자 목록을 조회할 수 없다")
    void getUsers_returnsForbidden_withoutAdminRole() throws Exception {
        mockMvc.perform(get(USERS_URL).with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(USERS_URL)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("페이지 입력 범위를 벗어나면 400을 반환한다")
    void getUsers_returnsBadRequest_whenPageInputIsInvalid() throws Exception {
        mockMvc.perform(get(USERS_URL)
                        .param("page", "-1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    @DisplayName("ADMIN 사용자가 대상 사용자의 부서를 변경한다")
    void changeDepartment_returnsUpdatedUser() throws Exception {
        AdminUserResponse response = new AdminUserResponse(
                10L,
                "홍길동",
                "hong@example.com",
                "hong",
                5L,
                "영업팀",
                UserStatus.ACTIVE,
                List.of("USER"),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0)
        );
        given(userCommandService.changeDepartment(10L, new ChangeDepartmentRequest(5L))).willReturn(response);

        mockMvc.perform(patch(USERS_URL + "/{userId}/department", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"departmentId":5}
                            """)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(10))
                .andExpect(jsonPath("$.data.departmentId").value(5))
                .andExpect(jsonPath("$.data.departmentName").value("영업팀"));
    }

    @Test
    @DisplayName("ADMIN이 아닌 사용자는 부서를 변경할 수 없다")
    void changeDepartment_returnsForbidden_withoutAdminRole() throws Exception {
        mockMvc.perform(patch(USERS_URL + "/{userId}/department", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"departmentId":5}
                            """)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("departmentId가 없으면 400을 반환한다")
    void changeDepartment_returnsBadRequest_whenDepartmentIdIsMissing() throws Exception {
        mockMvc.perform(patch(USERS_URL + "/{userId}/department", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }
}
