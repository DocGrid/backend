package com.opensource.docgrid.domain.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.auth.dto.request.LoginRequest;
import com.opensource.docgrid.domain.auth.dto.request.SignupRequest;
import com.opensource.docgrid.domain.auth.dto.response.LoginResponse;
import com.opensource.docgrid.domain.auth.dto.response.MeResponse;
import com.opensource.docgrid.domain.auth.dto.response.SignupResponse;
import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.service.command.AuthCommandService;
import com.opensource.docgrid.domain.auth.service.query.AuthQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Auth", description = "인증 관련 API")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthCommandService authCommandService;
    private final AuthQueryService authQueryService;

    @Operation(summary = "회원가입", description = "이메일, 비밀번호, 이름, 부서 ID로 신규 계정을 생성합니다. 가입 시 USER 역할이 자동으로 부여됩니다.")
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@RequestBody @Valid SignupRequest request) {
        return ResponseUtils.created(authCommandService.signup(request));
    }

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인합니다. 성공 시 Bearer 액세스 토큰을 반환합니다. 이후 인증이 필요한 API는 Authorization: Bearer {token} 헤더에 토큰을 포함하세요.")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody @Valid LoginRequest request) {
        return ResponseUtils.ok(authCommandService.login(request));
    }

    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자의 정보를 반환합니다. Authorization: Bearer {token} 헤더가 필요합니다.")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> getMe(@Parameter(hidden = true) @CurrentUser Long userId) {
        return ResponseUtils.ok(authQueryService.getMe(userId));
    }

    @Operation(summary = "로그아웃", description = "현재 사용 중인 액세스 토큰을 무효화합니다. 무효화된 토큰은 만료 전이라도 이후 요청에 사용할 수 없습니다. Authorization: Bearer {token} 헤더가 필요합니다.")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        authCommandService.logout(JwtProvider.resolveToken(request));
        return ResponseUtils.noContent();
    }
}
