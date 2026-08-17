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
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.auth.fixture.AuthFixture;
import com.opensource.docgrid.domain.user.converter.AdminUserConverter;
import com.opensource.docgrid.domain.user.dto.request.ChangeDepartmentRequest;
import com.opensource.docgrid.domain.user.dto.response.AdminUserResponse;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.enums.CommonStatus;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserCommandService 단위 테스트")
class UserCommandServiceTest {

    @InjectMocks private UserCommandService userCommandService;
    @Mock private UserRepository userRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private AdminUserConverter adminUserConverter;

    @Test
    @DisplayName("정상 케이스: 관리자가 사용자의 부서를 변경한다")
    void changeDepartment_success() {
        // Given
        Department oldDepartment = AuthFixture.createDepartment();
        User targetUser = AuthFixture.createUser(oldDepartment);

        Department newDepartment = Department.builder()
                .name("영업팀")
                .code("SALES")
                .status(CommonStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(newDepartment, "id", 20L);

        Role role = AuthFixture.createRole();
        UserRole userRole = AuthFixture.createUserRole(targetUser, role);

        given(userRepository.findById(AuthFixture.USER_ID)).willReturn(Optional.of(targetUser));
        given(departmentRepository.findById(20L)).willReturn(Optional.of(newDepartment));
        given(userRoleRepository.findAllWithRoleByUserId(AuthFixture.USER_ID)).willReturn(List.of(userRole));

        AdminUserResponse expected = new AdminUserResponse(
                AuthFixture.USER_ID, targetUser.getName(), targetUser.getEmail(), null,
                20L, "영업팀", targetUser.getStatus(), List.of(role.getCode()), null, null
        );
        given(adminUserConverter.toResponse(targetUser, List.of(role.getCode()))).willReturn(expected);

        // When
        AdminUserResponse result = userCommandService.changeDepartment(
                AuthFixture.USER_ID, new ChangeDepartmentRequest(20L));

        // Then
        assertThat(result).isEqualTo(expected);
        assertThat(targetUser.getDepartment()).isEqualTo(newDepartment);
        then(userRepository).should().findById(AuthFixture.USER_ID);
        then(departmentRepository).should().findById(20L);
    }

    @Test
    @DisplayName("예외 케이스: 대상 사용자가 없으면 USER_NOT_FOUND 예외가 발생한다")
    void changeDepartment_throws_whenUserNotFound() {
        given(userRepository.findById(anyLong())).willReturn(Optional.empty());

        assertThatThrownBy(() -> userCommandService.changeDepartment(999L, new ChangeDepartmentRequest(20L)))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);

        then(departmentRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: 부서가 없거나 비활성 상태면 DEPARTMENT_NOT_FOUND 예외가 발생한다")
    void changeDepartment_throws_whenDepartmentNotFoundOrInactive() {
        Department department = AuthFixture.createDepartment();
        User targetUser = AuthFixture.createUser(department);

        given(userRepository.findById(AuthFixture.USER_ID)).willReturn(Optional.of(targetUser));
        given(departmentRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userCommandService.changeDepartment(
                AuthFixture.USER_ID, new ChangeDepartmentRequest(999L)))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DEPARTMENT_NOT_FOUND);
    }
}
