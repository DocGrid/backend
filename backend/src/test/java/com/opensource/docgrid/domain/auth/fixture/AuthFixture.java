package com.opensource.docgrid.domain.auth.fixture;

import java.time.LocalDateTime;

import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.enums.CommonStatus;
import com.opensource.docgrid.domain.user.enums.UserStatus;

public class AuthFixture {

    public static final Long USER_ID = 1L;
    public static final Long DEPARTMENT_ID = 10L;
    public static final Long ROLE_ID = 100L;
    public static final String EMAIL = "test@docgrid.com";
    public static final String PASSWORD = "password1234";
    public static final String PASSWORD_HASH = "encoded-password";
    public static final String NAME = "테스트유저";
    public static final String ROLE_CODE = "USER";

    private AuthFixture() {
    }

    public static Department createDepartment() {
        Department department = Department.builder()
                .name("개발팀")
                .code("DEV")
                .status(CommonStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(department, "id", DEPARTMENT_ID);
        return department;
    }

    public static Role createRole() {
        Role role = Role.builder()
                .name("일반 사용자")
                .code(ROLE_CODE)
                .build();
        ReflectionTestUtils.setField(role, "id", ROLE_ID);
        return role;
    }

    public static User createUser(Department department) {
        return createUser(department, UserStatus.ACTIVE);
    }

    public static User createUser(Department department, UserStatus status) {
        User user = User.builder()
                .department(department)
                .email(EMAIL)
                .passwordHash(PASSWORD_HASH)
                .name(NAME)
                .status(status)
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    public static UserRole createUserRole(User user, Role role) {
        return UserRole.builder()
                .user(user)
                .role(role)
                .assignedBy(user)
                .assignedAt(LocalDateTime.now())
                .build();
    }
}
