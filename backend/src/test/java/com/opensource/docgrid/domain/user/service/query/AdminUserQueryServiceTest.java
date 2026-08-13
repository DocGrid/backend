package com.opensource.docgrid.domain.user.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.opensource.docgrid.domain.user.converter.AdminUserConverter;
import com.opensource.docgrid.domain.user.dto.response.AdminUserResponse;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.common.response.PageResponse;

/**
 * 관리자 사용자 목록의 검색어 정규화, 기본 삭제 제외, 역할 일괄 조회와 Pagination을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserQueryService 단위 테스트")
class AdminUserQueryServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private AdminUserConverter adminUserConverter;

    @InjectMocks private AdminUserQueryService adminUserQueryService;

    @Test
    @DisplayName("검색·부서·상태 필터와 사용자 역할을 결합해 최근 가입순 페이지를 반환한다")
    void getUsers_returnsFilteredUsersWithRoles() {
        User user = org.mockito.Mockito.mock(User.class);
        UserRole userRole = org.mockito.Mockito.mock(UserRole.class);
        Role role = org.mockito.Mockito.mock(Role.class);
        AdminUserResponse response = org.mockito.Mockito.mock(AdminUserResponse.class);
        given(user.getId()).willReturn(10L);
        given(userRole.getUser()).willReturn(user);
        given(userRole.getRole()).willReturn(role);
        given(role.getCode()).willReturn("ADMIN");
        given(userRepository.findAdminUsers(
                org.mockito.ArgumentMatchers.eq("Alice"),
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq(UserStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(UserStatus.DELETED),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(user), PageRequest.of(1, 5), 6));
        given(userRoleRepository.findAllWithRoleByUserIdIn(List.of(10L))).willReturn(List.of(userRole));
        given(adminUserConverter.toResponse(user, List.of("ADMIN"))).willReturn(response);

        PageResponse<AdminUserResponse> result = adminUserQueryService.getUsers(
                "  Alice  ",
                3L,
                UserStatus.ACTIVE,
                1,
                5
        );

        assertThat(result.content()).containsExactly(response);
        assertThat(result.totalElements()).isEqualTo(6);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        then(userRepository).should().findAdminUsers(
                org.mockito.ArgumentMatchers.eq("Alice"),
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq(UserStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(UserStatus.DELETED),
                pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    @DisplayName("빈 검색어와 상태 미입력은 필터 없음과 DELETED 기본 제외 조건으로 전달한다")
    void getUsers_normalizesBlankKeyword_andSkipsRoleQueryForEmptyPage() {
        given(userRepository.findAdminUsers(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(UserStatus.DELETED),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageResponse<AdminUserResponse> result = adminUserQueryService.getUsers("   ", null, null, 0, 20);

        assertThat(result.content()).isEmpty();
        then(userRoleRepository).shouldHaveNoInteractions();
    }
}
