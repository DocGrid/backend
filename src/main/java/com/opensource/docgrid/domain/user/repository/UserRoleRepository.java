package com.opensource.docgrid.domain.user.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.user.entity.UserRole;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    @Query("SELECT ur FROM UserRole ur JOIN FETCH ur.role WHERE ur.user.id = :userId")
    List<UserRole> findAllWithRoleByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(ur) > 0 FROM UserRole ur WHERE ur.user.id = :userId AND ur.role.code = :roleCode")
    boolean existsByUserIdAndRoleCode(@Param("userId") Long userId, @Param("roleCode") String roleCode);
}
