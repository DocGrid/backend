package com.opensource.docgrid.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;

/**
 * 관리자 사용자 목록 조회를 실제 PostgreSQL Repository 계층에서 검증하는 테스트.
 *
 * <p>keyword가 null일 때 Hibernate가 파라미터 타입을 잘못 추론해 "function lower(bytea) does
 * not exist" 오류가 나던 회귀를 방지한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("UserRepository 테스트")
class UserRepositoryTest {

    // id 내림차순 정렬을 명시해서, 스키마에 다른 테스트가 남긴 row가 쌓여 있어도
    // 방금 만든(=가장 큰 id) row가 항상 1페이지 안에 들어오도록 보장한다.
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    @DisplayName("keyword 없이 조회해도 예외 없이 사용자 목록을 반환한다")
    void findAdminUsers_succeeds_whenKeywordIsNull() {
        User user = saveUser("keyword-null-test@example.com", "키워드없음테스트");

        assertThatCode(() -> userRepository.findAdminUsers(
                null, null, null, UserStatus.DELETED, PageRequest.of(0, 20, NEWEST_FIRST)))
                .doesNotThrowAnyException();

        Page<User> result = userRepository.findAdminUsers(
                null, null, null, UserStatus.DELETED, PageRequest.of(0, 20, NEWEST_FIRST));
        assertThat(result.getContent()).extracting(User::getId).contains(user.getId());
    }

    @Test
    @DisplayName("keyword로 이름·이메일을 대소문자 구분 없이 검색한다")
    void findAdminUsers_filtersByKeyword() {
        User matched = saveUser("needle-user@example.com", "바늘찾기유저");
        saveUser("other-user@example.com", "관련없는유저");

        Page<User> result = userRepository.findAdminUsers(
                "NEEDLE", null, null, UserStatus.DELETED, PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(result.getContent())
                .extracting(User::getId)
                .containsExactly(matched.getId());
    }

    private User saveUser(String email, String name) {
        Department department = departmentRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Seed 부서 데이터가 없습니다."));
        User user = User.builder()
                .email(email)
                .passwordHash("password-hash")
                .name(name)
                .department(department)
                .status(UserStatus.ACTIVE)
                .build();
        return userRepository.save(user);
    }
}
