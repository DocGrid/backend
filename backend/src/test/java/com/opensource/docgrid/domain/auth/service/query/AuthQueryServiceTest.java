package com.opensource.docgrid.domain.auth.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.auth.dto.response.MeResponse;
import com.opensource.docgrid.domain.auth.fixture.AuthFixture;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthQueryService 단위 테스트")
class AuthQueryServiceTest {

    @InjectMocks
    private AuthQueryService authQueryService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Test
    @DisplayName("가입된 사용자 ID로 조회하면 역할 목록이 포함된 MeResponse를 반환한다")
    void getMe_succeeds_when_userExists() {
        Department department = AuthFixture.createDepartment();
        User user = AuthFixture.createUser(department);
        given(userRepository.findById(AuthFixture.USER_ID)).willReturn(Optional.of(user));
        given(userRoleRepository.findRoleCodesByUserId(AuthFixture.USER_ID)).willReturn(List.of("USER"));

        MeResponse result = authQueryService.getMe(AuthFixture.USER_ID);

        assertThat(result.userId()).isEqualTo(AuthFixture.USER_ID);
        assertThat(result.email()).isEqualTo(AuthFixture.EMAIL);
        assertThat(result.departmentId()).isEqualTo(AuthFixture.DEPARTMENT_ID);
        assertThat(result.roles()).containsExactly("USER");
    }

    @Test
    @DisplayName("존재하지 않는 사용자 ID로 조회하면 USER_NOT_FOUND 예외가 발생한다")
    void getMe_throws_when_userNotFound() {
        given(userRepository.findById(AuthFixture.USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authQueryService.getMe(AuthFixture.USER_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
    }
}
