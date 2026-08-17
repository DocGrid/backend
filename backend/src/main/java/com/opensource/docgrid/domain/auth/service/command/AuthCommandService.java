package com.opensource.docgrid.domain.auth.service.command;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.auth.dto.request.LoginRequest;
import com.opensource.docgrid.domain.auth.dto.request.SignupRequest;
import com.opensource.docgrid.domain.auth.dto.response.LoginResponse;
import com.opensource.docgrid.domain.auth.dto.response.SignupResponse;
import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.enums.CommonStatus;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Transactional
@Service
@RequiredArgsConstructor
public class AuthCommandService {

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final TokenBlacklistService tokenBlacklistService;

    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            log.error("회원가입 실패 - 이메일 중복: {}", request.email());
            throw new DocGridException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        Department department = departmentRepository.findById(request.departmentId())
                .filter(d -> d.getStatus() == CommonStatus.ACTIVE)
                .orElseThrow(() -> new DocGridException(ErrorCode.DEPARTMENT_NOT_FOUND));

        Role userRole = roleRepository.findByCode("USER")
                .orElseThrow(() -> {
                    log.error("USER role이 존재하지 않습니다. Seed 데이터를 확인하세요.");
                    return new DocGridException(ErrorCode.ROLE_NOT_FOUND);
                });

        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .name(request.name())
                .department(department)
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(user);

        UserRole userRoleEntity = UserRole.builder()
                .user(user)
                .role(userRole)
                .assignedBy(user)
                .assignedAt(LocalDateTime.now())
                .build();
        userRoleRepository.save(userRoleEntity);

        return SignupResponse.of(user, List.of(userRole.getCode()));
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new DocGridException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new DocGridException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new DocGridException(ErrorCode.ACCOUNT_INACTIVE);
        }

        user.recordLogin(LocalDateTime.now());

        List<String> roles = userRoleRepository.findRoleCodesByUserId(user.getId());

        String token = jwtProvider.generateToken(user.getId(), user.getEmail());

        return LoginResponse.of(token, jwtProvider.getExpirationSeconds(), user.getId(), user.getEmail(), roles);
    }

    public void logout(String token) {
        Claims claims = jwtProvider.getClaimsIfValid(token);
        if (claims == null) {
            return;
        }

        String jti = claims.get("jti", String.class);
        if (jti == null) {
            return;
        }

        long remainingMillis = Duration.between(Instant.now(), claims.getExpiration().toInstant()).toMillis();
        if (remainingMillis > 0) {
            long remainingSeconds = (remainingMillis + 999) / 1000;
            tokenBlacklistService.blacklist(jti, remainingSeconds);
        }
    }
}
