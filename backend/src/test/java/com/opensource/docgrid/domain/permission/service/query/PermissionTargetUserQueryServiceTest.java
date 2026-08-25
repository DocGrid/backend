package com.opensource.docgrid.domain.permission.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.opensource.docgrid.domain.permission.dto.response.PermissionTargetUserResponse;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 리소스 ADMIN 인가와 동명이인 구분용 사용자 검색 응답 경계를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionTargetUserQueryService 테스트")
class PermissionTargetUserQueryServiceTest {

    @Mock private PermissionQueryService permissionQueryService;
    @Mock private UserRepository userRepository;
    @InjectMocks private PermissionTargetUserQueryService service;

    @Test
    @DisplayName("문서 ADMIN은 이름·이메일·부서·ID가 포함된 활성 사용자 결과를 조회한다")
    void searchForDocument_returnsDisambiguatedActiveUsers() {
        User user = mock(User.class);
        Department department = mock(Department.class);
        given(permissionQueryService.canAdminDocument(10L, 5L)).willReturn(true);
        given(user.getId()).willReturn(20L);
        given(user.getName()).willReturn("김기민");
        given(user.getEmail()).willReturn("gimin@example.com");
        given(user.getDepartment()).willReturn(department);
        given(department.getId()).willReturn(3L);
        given(department.getName()).willReturn("개발팀");
        given(userRepository.findAdminUsers(
            eq("김기민"),
            isNull(),
            eq(UserStatus.ACTIVE),
            eq(UserStatus.DELETED),
            any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(user)));

        List<PermissionTargetUserResponse> result = service.searchForDocument(10L, 5L, " 김기민 ");

        assertThat(result).containsExactly(new PermissionTargetUserResponse(
            20L,
            "김기민",
            "gimin@example.com",
            3L,
            "개발팀"
        ));
    }

    @Test
    @DisplayName("컬렉션 ADMIN 권한이 없으면 사용자 검색을 실행하지 않는다")
    void searchForCollection_throws_whenResourceAdminIsDenied() {
        given(permissionQueryService.canAdminCollection(10L, 7L)).willReturn(false);

        assertThatThrownBy(() -> service.searchForCollection(10L, 7L, "김기민"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(userRepository);
    }
}
