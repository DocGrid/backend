package com.opensource.docgrid.domain.user.converter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.user.dto.response.AdminUserResponse;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.User;

/**
 * 사용자와 사전 조회된 역할 코드를 민감 정보가 없는 관리자 목록 응답으로 변환한다.
 */
@Component
public class AdminUserConverter {

    public AdminUserResponse toResponse(User user, List<String> roles) {
        Department department = user.getDepartment();
        return new AdminUserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getNickname(),
                department != null ? department.getId() : null,
                department != null ? department.getName() : null,
                user.getStatus(),
                List.copyOf(roles),
                user.getLastLoginAt(),
                user.getCreatedAt()
        );
    }
}
