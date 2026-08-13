package com.opensource.docgrid.domain.user.service.command;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.user.dto.request.AssignRoleRequest;
import com.opensource.docgrid.domain.user.dto.response.UserRoleResponse;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class UserRoleCommandService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    // 관리자가 다른 사용자에게 역할을 부여
    public UserRoleResponse assignRole(Long targetUserId, Long adminUserId, AssignRoleRequest request) {
        // 타겟 사용자 조회
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));

        Role role = roleRepository.findByCode(request.roleCode())
                .orElseThrow(() -> new DocGridException(ErrorCode.ROLE_NOT_FOUND));

        if (userRoleRepository.existsByUserIdAndRoleCode(targetUserId, request.roleCode())) {
            throw new DocGridException(ErrorCode.ROLE_ALREADY_ASSIGNED);
        }

        User admin = userRepository.getReferenceById(adminUserId);

        UserRole userRole = UserRole.builder()
                .user(targetUser)
                .role(role)
                .assignedBy(admin) // 누가 역할을 부여했는지 기록
                .assignedAt(LocalDateTime.now())
                .build();
        userRoleRepository.save(userRole);

        List<String> roles = userRoleRepository.findAllWithRoleByUserId(targetUserId).stream()
                .map(ur -> ur.getRole().getCode())
                .toList();

        return UserRoleResponse.of(targetUser, roles);
    }
}
