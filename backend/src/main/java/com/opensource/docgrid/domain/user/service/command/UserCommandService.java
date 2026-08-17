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
        // 1. 대상 사용자를 먼저 조회한다 — 존재하지 않는 사용자는 부서 조회보다 우선 404로 응답한다.
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));

        // 2. 회원가입(AuthCommandService.signup)과 동일하게 존재 + ACTIVE 상태인 부서만 허용한다.
        //    비활성 부서로 소속을 옮기면 신규 가입도 불가능한 부서에 기존 사용자가 남게 되므로 금지한다.
        Department department = departmentRepository.findById(request.departmentId())
                .filter(d -> d.getStatus() == CommonStatus.ACTIVE)
                .orElseThrow(() -> new DocGridException(ErrorCode.DEPARTMENT_NOT_FOUND));

        // 3. dirty checking으로 저장되므로 명시적 save() 호출은 하지 않는다.
        targetUser.changeDepartment(department);

        // 4. 목록 조회(AdminUserQueryService)와 동일한 응답 계약을 맞추기 위해 역할 코드를 함께 조회해 변환한다.
        List<String> roles = userRoleRepository.findAllWithRoleByUserId(targetUserId).stream()
                .map(ur -> ur.getRole().getCode())
                .toList();

        return adminUserConverter.toResponse(targetUser, roles);
    }
}
