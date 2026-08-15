package com.opensource.docgrid.domain.user.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    /**
     * 관리자 사용자 화면의 검색·부서·상태 필터를 적용하고 부서를 함께 페이지 조회한다.
     */
    @Query(
            value = """
                    SELECT u
                    FROM User u
                    LEFT JOIN FETCH u.department
                    WHERE (
                        (:status IS NULL AND u.status <> :deletedStatus)
                        OR (:status IS NOT NULL AND u.status = :status)
                    )
                      AND (
                        :keyword IS NULL
                        OR LOWER(u.name) LIKE CONCAT(CONCAT('%', LOWER(CAST(:keyword AS string))), '%')
                        OR LOWER(u.email) LIKE CONCAT(CONCAT('%', LOWER(CAST(:keyword AS string))), '%')
                      )
                      AND (:departmentId IS NULL OR u.department.id = :departmentId)
                    """,
            countQuery = """
                    SELECT COUNT(u)
                    FROM User u
                    WHERE (
                        (:status IS NULL AND u.status <> :deletedStatus)
                        OR (:status IS NOT NULL AND u.status = :status)
                    )
                      AND (
                        :keyword IS NULL
                        OR LOWER(u.name) LIKE CONCAT(CONCAT('%', LOWER(CAST(:keyword AS string))), '%')
                        OR LOWER(u.email) LIKE CONCAT(CONCAT('%', LOWER(CAST(:keyword AS string))), '%')
                      )
                      AND (:departmentId IS NULL OR u.department.id = :departmentId)
                    """
    )
    Page<User> findAdminUsers(
            @Param("keyword") String keyword,
            @Param("departmentId") Long departmentId,
            @Param("status") UserStatus status,
            @Param("deletedStatus") UserStatus deletedStatus,
            Pageable pageable
    );
}
