package com.opensource.docgrid.domain.auth.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.opensource.docgrid.domain.auth.dto.request.LoginRequest;
import com.opensource.docgrid.domain.auth.dto.request.SignupRequest;
import com.opensource.docgrid.domain.auth.dto.response.LoginResponse;
import com.opensource.docgrid.domain.auth.dto.response.SignupResponse;
import com.opensource.docgrid.domain.auth.fixture.AuthFixture;
import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.CommonStatus;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.jsonwebtoken.Claims;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthCommandService 단위 테스트")
class AuthCommandServiceTest {

    @InjectMocks
    private AuthCommandService authCommandService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    // ==================== signup ====================

    @Test
    @DisplayName("정상 요청으로 회원가입하면 USER 역할이 부여된 SignupResponse를 반환한다")
    void signup_succeeds_when_validRequest() {
        Department department = AuthFixture.createDepartment();
        Role userRole = AuthFixture.createRole();
        SignupRequest request = new SignupRequest(AuthFixture.EMAIL, AuthFixture.PASSWORD, AuthFixture.NAME, AuthFixture.DEPARTMENT_ID);

        given(userRepository.existsByEmail(AuthFixture.EMAIL)).willReturn(false);
        given(departmentRepository.findById(AuthFixture.DEPARTMENT_ID)).willReturn(Optional.of(department));
        given(roleRepository.findByCode("USER")).willReturn(Optional.of(userRole));
        given(passwordEncoder.encode(AuthFixture.PASSWORD)).willReturn(AuthFixture.PASSWORD_HASH);

        SignupResponse result = authCommandService.signup(request);

        assertThat(result.email()).isEqualTo(AuthFixture.EMAIL);
        assertThat(result.name()).isEqualTo(AuthFixture.NAME);
        assertThat(result.departmentId()).isEqualTo(AuthFixture.DEPARTMENT_ID);
        assertThat(result.roles()).containsExactly("USER");
        then(userRepository).should().save(any(User.class));
        then(userRoleRepository).should().save(any());
    }

    @Test
    @DisplayName("이미 사용 중인 이메일로 가입하면 EMAIL_ALREADY_EXISTS 예외가 발생한다")
    void signup_throws_when_emailAlreadyExists() {
        SignupRequest request = new SignupRequest(AuthFixture.EMAIL, AuthFixture.PASSWORD, AuthFixture.NAME, AuthFixture.DEPARTMENT_ID);
        given(userRepository.existsByEmail(AuthFixture.EMAIL)).willReturn(true);

        assertThatThrownBy(() -> authCommandService.signup(request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("비밀번호가 이메일과 같으면 WEAK_PASSWORD 예외가 발생한다")
    void signup_throws_when_passwordEqualsEmail() {
        SignupRequest request = new SignupRequest(AuthFixture.EMAIL, AuthFixture.EMAIL, AuthFixture.NAME, AuthFixture.DEPARTMENT_ID);
        given(userRepository.existsByEmail(AuthFixture.EMAIL)).willReturn(false);

        assertThatThrownBy(() -> authCommandService.signup(request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEAK_PASSWORD);
    }

    @Test
    @DisplayName("비밀번호가 이름과 같으면 WEAK_PASSWORD 예외가 발생한다")
    void signup_throws_when_passwordEqualsName() {
        SignupRequest request = new SignupRequest(AuthFixture.EMAIL, AuthFixture.NAME, AuthFixture.NAME, AuthFixture.DEPARTMENT_ID);
        given(userRepository.existsByEmail(AuthFixture.EMAIL)).willReturn(false);

        assertThatThrownBy(() -> authCommandService.signup(request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WEAK_PASSWORD);
    }

    @Test
    @DisplayName("존재하지 않는 부서로 가입하면 DEPARTMENT_NOT_FOUND 예외가 발생한다")
    void signup_throws_when_departmentNotFound() {
        SignupRequest request = new SignupRequest(AuthFixture.EMAIL, AuthFixture.PASSWORD, AuthFixture.NAME, AuthFixture.DEPARTMENT_ID);
        given(userRepository.existsByEmail(AuthFixture.EMAIL)).willReturn(false);
        given(departmentRepository.findById(AuthFixture.DEPARTMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authCommandService.signup(request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DEPARTMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("비활성 부서로 가입하면 DEPARTMENT_NOT_FOUND 예외가 발생한다")
    void signup_throws_when_departmentInactive() {
        Department inactiveDepartment = Department.builder()
                .name("폐지된 부서").code("OLD").status(CommonStatus.INACTIVE).build();
        SignupRequest request = new SignupRequest(AuthFixture.EMAIL, AuthFixture.PASSWORD, AuthFixture.NAME, AuthFixture.DEPARTMENT_ID);
        given(userRepository.existsByEmail(AuthFixture.EMAIL)).willReturn(false);
        given(departmentRepository.findById(AuthFixture.DEPARTMENT_ID)).willReturn(Optional.of(inactiveDepartment));

        assertThatThrownBy(() -> authCommandService.signup(request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DEPARTMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("USER role이 존재하지 않으면 ROLE_NOT_FOUND 예외가 발생한다")
    void signup_throws_when_userRoleNotFound() {
        Department department = AuthFixture.createDepartment();
        SignupRequest request = new SignupRequest(AuthFixture.EMAIL, AuthFixture.PASSWORD, AuthFixture.NAME, AuthFixture.DEPARTMENT_ID);
        given(userRepository.existsByEmail(AuthFixture.EMAIL)).willReturn(false);
        given(departmentRepository.findById(AuthFixture.DEPARTMENT_ID)).willReturn(Optional.of(department));
        given(roleRepository.findByCode("USER")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authCommandService.signup(request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROLE_NOT_FOUND);
    }

    // ==================== login ====================

    @Test
    @DisplayName("정상 자격증명으로 로그인하면 토큰을 발급하고 마지막 로그인 시각을 기록한다")
    void login_succeeds_when_validCredentials() {
        Department department = AuthFixture.createDepartment();
        User user = AuthFixture.createUser(department);
        LoginRequest request = new LoginRequest(AuthFixture.EMAIL, AuthFixture.PASSWORD);

        given(userRepository.findByEmail(AuthFixture.EMAIL)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(AuthFixture.PASSWORD, AuthFixture.PASSWORD_HASH)).willReturn(true);
        given(userRoleRepository.findRoleCodesByUserId(AuthFixture.USER_ID)).willReturn(List.of("USER"));
        given(jwtProvider.generateToken(AuthFixture.USER_ID, AuthFixture.EMAIL)).willReturn("access-token");
        given(jwtProvider.getExpirationSeconds()).willReturn(3600L);

        LoginResponse result = authCommandService.login(request);

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.userId()).isEqualTo(AuthFixture.USER_ID);
        assertThat(result.roles()).containsExactly("USER");
        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인하면 INVALID_CREDENTIALS 예외가 발생한다")
    void login_throws_when_emailNotFound() {
        LoginRequest request = new LoginRequest(AuthFixture.EMAIL, AuthFixture.PASSWORD);
        given(userRepository.findByEmail(AuthFixture.EMAIL)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authCommandService.login(request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("비밀번호가 일치하지 않으면 INVALID_CREDENTIALS 예외가 발생한다")
    void login_throws_when_passwordMismatch() {
        Department department = AuthFixture.createDepartment();
        User user = AuthFixture.createUser(department);
        LoginRequest request = new LoginRequest(AuthFixture.EMAIL, "wrong-password");

        given(userRepository.findByEmail(AuthFixture.EMAIL)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

        assertThatThrownBy(() -> authCommandService.login(request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("비활성화된 계정으로 로그인하면 ACCOUNT_INACTIVE 예외가 발생한다")
    void login_throws_when_accountInactive() {
        Department department = AuthFixture.createDepartment();
        User user = AuthFixture.createUser(department, UserStatus.INACTIVE);
        LoginRequest request = new LoginRequest(AuthFixture.EMAIL, AuthFixture.PASSWORD);

        given(userRepository.findByEmail(AuthFixture.EMAIL)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(AuthFixture.PASSWORD, AuthFixture.PASSWORD_HASH)).willReturn(true);

        assertThatThrownBy(() -> authCommandService.login(request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_INACTIVE);
    }

    // ==================== logout ====================

    @Test
    @DisplayName("유효한 토큰으로 로그아웃하면 잔여 만료 시간만큼 블랙리스트에 등록한다")
    void logout_blacklistsToken_whenTokenValid() {
        String token = "access-token";
        String jti = "test-jti";
        Claims claims = mock(Claims.class);
        given(jwtProvider.getClaimsIfValid(token)).willReturn(claims);
        given(claims.get("jti", String.class)).willReturn(jti);
        given(claims.getExpiration()).willReturn(Date.from(Instant.now().plusSeconds(600)));

        authCommandService.logout(token);

        then(tokenBlacklistService).should().blacklist(eq(jti), longThat(ttl -> ttl > 0 && ttl <= 600));
    }

    @Test
    @DisplayName("유효하지 않은 토큰으로 로그아웃하면 블랙리스트에 등록하지 않는다")
    void logout_doesNothing_whenTokenInvalid() {
        String token = "invalid-token";
        given(jwtProvider.getClaimsIfValid(token)).willReturn(null);

        authCommandService.logout(token);

        then(tokenBlacklistService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("jti가 없는 토큰으로 로그아웃하면 블랙리스트에 등록하지 않는다")
    void logout_doesNothing_whenJtiMissing() {
        String token = "legacy-token-without-jti";
        Claims claims = mock(Claims.class);
        given(jwtProvider.getClaimsIfValid(token)).willReturn(claims);
        given(claims.get("jti", String.class)).willReturn(null);

        authCommandService.logout(token);

        then(tokenBlacklistService).shouldHaveNoInteractions();
    }
}
