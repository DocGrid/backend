package com.opensource.docgrid.domain.user.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.auth.fixture.AuthFixture;
import com.opensource.docgrid.domain.auth.jwt.RoleAuthorityService;
import com.opensource.docgrid.domain.user.dto.request.AssignRoleRequest;
import com.opensource.docgrid.domain.user.dto.response.UserRoleResponse;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 관리자의 역할 부여·회수 명령(assignRole/revokeRole)과 role 캐시 무효화 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserRoleCommandService 단위 테스트")
class UserRoleCommandServiceTest {

    @InjectMocks private UserRoleCommandService userRoleCommandService;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleAuthorityService roleAuthorityService;

    @Test
    @DisplayName("정상 케이스: 관리자가 사용자에게 역할을 부여하면 캐시를 무효화한다")
    void assignRole_success_invalidatesCache() {
        Department department = AuthFixture.createDepartment();
        User targetUser = AuthFixture.createUser(department);
        Role role = AuthFixture.createRole();

        given(userRepository.findById(AuthFixture.USER_ID)).willReturn(Optional.of(targetUser));
        given(roleRepository.findByCode("USER")).willReturn(Optional.of(role));
        given(userRoleRepository.existsByUserIdAndRoleCode(AuthFixture.USER_ID, "USER")).willReturn(false);
        given(userRepository.getReferenceById(99L)).willReturn(targetUser);
        given(userRoleRepository.findAllWithRoleByUserId(AuthFixture.USER_ID))
                .willReturn(List.of(AuthFixture.createUserRole(targetUser, role)));

        UserRoleResponse result = userRoleCommandService.assignRole(
                AuthFixture.USER_ID, 99L, new AssignRoleRequest("USER"));

        assertThat(result.roles()).containsExactly("USER");
        then(roleAuthorityService).should().invalidate(AuthFixture.USER_ID);
    }

    @Test
    @DisplayName("예외 케이스: 이미 부여된 역할이면 ROLE_ALREADY_ASSIGNED 예외가 발생한다")
    void assignRole_throws_whenAlreadyAssigned() {
        Department department = AuthFixture.createDepartment();
        User targetUser = AuthFixture.createUser(department);
        Role role = AuthFixture.createRole();

        given(userRepository.findById(AuthFixture.USER_ID)).willReturn(Optional.of(targetUser));
        given(roleRepository.findByCode("USER")).willReturn(Optional.of(role));
        given(userRoleRepository.existsByUserIdAndRoleCode(AuthFixture.USER_ID, "USER")).willReturn(true);

        assertThatThrownBy(() -> userRoleCommandService.assignRole(
                AuthFixture.USER_ID, 99L, new AssignRoleRequest("USER")))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROLE_ALREADY_ASSIGNED);

        then(roleAuthorityService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("정상 케이스: 관리자가 부여된 역할을 회수하면 캐시를 무효화하고 남은 역할을 반환한다")
    void revokeRole_success_invalidatesCache() {
        Department department = AuthFixture.createDepartment();
        User targetUser = AuthFixture.createUser(department);
        Role adminRole = AuthFixture.createRole();
        UserRole userRole = AuthFixture.createUserRole(targetUser, adminRole);

        given(userRepository.findById(AuthFixture.USER_ID)).willReturn(Optional.of(targetUser));
        given(userRoleRepository.findByUserIdAndRoleCode(AuthFixture.USER_ID, "ADMIN"))
                .willReturn(Optional.of(userRole));
        given(userRoleRepository.findAllWithRoleByUserId(AuthFixture.USER_ID)).willReturn(List.of());

        UserRoleResponse result = userRoleCommandService.revokeRole(AuthFixture.USER_ID, "ADMIN");

        assertThat(result.roles()).isEmpty();
        then(userRoleRepository).should().delete(userRole);
        then(roleAuthorityService).should().invalidate(AuthFixture.USER_ID);
    }

    @Test
    @DisplayName("예외 케이스: 대상 사용자가 없으면 USER_NOT_FOUND 예외가 발생한다")
    void revokeRole_throws_whenUserNotFound() {
        given(userRepository.findById(anyLong())).willReturn(Optional.empty());

        assertThatThrownBy(() -> userRoleCommandService.revokeRole(999L, "ADMIN"))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);

        then(roleAuthorityService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: 부여되지 않은 역할을 회수하려 하면 ROLE_NOT_ASSIGNED 예외가 발생한다")
    void revokeRole_throws_whenRoleNotAssigned() {
        Department department = AuthFixture.createDepartment();
        User targetUser = AuthFixture.createUser(department);

        given(userRepository.findById(AuthFixture.USER_ID)).willReturn(Optional.of(targetUser));
        given(userRoleRepository.findByUserIdAndRoleCode(AuthFixture.USER_ID, "ADMIN"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> userRoleCommandService.revokeRole(AuthFixture.USER_ID, "ADMIN"))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROLE_NOT_ASSIGNED);

        then(roleAuthorityService).shouldHaveNoInteractions();
    }
}
