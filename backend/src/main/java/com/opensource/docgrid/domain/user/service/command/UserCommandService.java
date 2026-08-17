package com.opensource.docgrid.domain.user.service.command;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.user.converter.AdminUserConverter;
import com.opensource.docgrid.domain.user.dto.request.ChangeDepartmentRequest;
import com.opensource.docgrid.domain.user.dto.response.AdminUserResponse;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.CommonStatus;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class UserCommandService {

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRoleRepository userRoleRepository;
    private final AdminUserConverter adminUserConverter;

    // 관리자가 다른 사용자의 소속 부서를 변경
    public AdminUserResponse changeDepartment(Long targetUserId, ChangeDepartmentRequest request) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));

        Department department = departmentRepository.findById(request.departmentId())
                .filter(d -> d.getStatus() == CommonStatus.ACTIVE)
                .orElseThrow(() -> new DocGridException(ErrorCode.DEPARTMENT_NOT_FOUND));

        targetUser.changeDepartment(department);

        List<String> roles = userRoleRepository.findAllWithRoleByUserId(targetUserId).stream()
                .map(ur -> ur.getRole().getCode())
                .toList();

        return adminUserConverter.toResponse(targetUser, roles);
    }
}
