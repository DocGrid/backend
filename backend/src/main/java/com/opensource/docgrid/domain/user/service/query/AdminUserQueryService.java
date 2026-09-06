package com.opensource.docgrid.domain.user.service.query;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.user.converter.AdminUserConverter;
import com.opensource.docgrid.domain.user.dto.response.AdminUserResponse;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.common.response.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 사용자 목록의 검색·필터·페이지 조회와 사용자별 역할 조합을 담당한다.
 * HTTP ADMIN 인가는 SecurityConfig가 담당하며 이 서비스는 조회 계약만 처리한다.
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class AdminUserQueryService {

    private static final Sort USER_SORT = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")
    );

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final AdminUserConverter adminUserConverter;

    /**
     * 관리자 화면의 사용자 검색 조건과 페이지 정보를 적용해 역할이 포함된 목록을 반환한다.
     *
     * @param keyword 이름 또는 이메일 검색어, 공백이면 필터를 적용하지 않음
     * @param departmentId 소속 부서 식별자, {@code null}이면 전체 부서
     * @param status 사용자 상태, {@code null}이면 삭제 사용자를 제외한 전체 상태
     * @param page 0부터 시작하는 페이지 번호
     * @param size 한 페이지의 사용자 수
     * @return 사용자 정보와 역할 코드가 결합된 페이지 응답
     */
    public PageResponse<AdminUserResponse> getUsers(
            String keyword,
            Long departmentId,
            UserStatus status,
            int page,
            int size) {
        // 1. 공백 검색어는 필터 없음으로 정규화하고 DELETED 기본 제외 조건으로 페이지를 조회한다.
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Page<User> users = userRepository.findAdminUsers(
                normalizedKeyword,
                departmentId,
                status,
                UserStatus.DELETED,
                PageRequest.of(page, size, USER_SORT)
        );

        // 2. 현재 페이지 사용자의 역할만 한 번에 조회해 사용자별 역할 조회를 반복하지 않는다.
        List<Long> userIds = users.getContent().stream().map(User::getId).toList();
        Map<Long, List<String>> roleCodesByUserId = userIds.isEmpty()
                ? Map.of()
                : userRoleRepository.findAllWithRoleByUserIdIn(userIds).stream()
                        .collect(Collectors.groupingBy(
                                userRole -> userRole.getUser().getId(),
                                Collectors.mapping(userRole -> userRole.getRole().getCode(), Collectors.toList())
                        ));

        // 3. 비밀번호 해시를 포함하지 않는 공개 관리자 응답으로 Transaction 안에서 변환한다.
        List<AdminUserResponse> content = users.getContent().stream()
                .map(user -> adminUserConverter.toResponse(
                        user,
                        roleCodesByUserId.getOrDefault(user.getId(), List.of())
                ))
                .toList();
        return PageResponse.from(users, content);
    }
}
