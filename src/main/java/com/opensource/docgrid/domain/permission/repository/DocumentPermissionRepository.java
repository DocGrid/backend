package com.opensource.docgrid.domain.permission.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.permission.entity.DocumentPermission;

public interface DocumentPermissionRepository extends JpaRepository<DocumentPermission, Long> {

    // ROLE live — 사용자 역할 기반 문서 읽기 권한 존재 여부
    @Query("""
            SELECT COUNT(dp) > 0 FROM DocumentPermission dp
            JOIN UserRole ur ON ur.role = dp.role
            WHERE dp.document.id = :documentId
              AND dp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND dp.canRead = true
              AND (dp.expiresAt IS NULL OR dp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleReadPermission(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // ROLE live — 사용자 역할 기반 문서 쓰기 권한 존재 여부
    @Query("""
            SELECT COUNT(dp) > 0 FROM DocumentPermission dp
            JOIN UserRole ur ON ur.role = dp.role
            WHERE dp.document.id = :documentId
              AND dp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND dp.canWrite = true
              AND (dp.expiresAt IS NULL OR dp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleWritePermission(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // ROLE live — 사용자 역할 기반 문서 관리 권한 존재 여부
    @Query("""
            SELECT COUNT(dp) > 0 FROM DocumentPermission dp
            JOIN UserRole ur ON ur.role = dp.role
            WHERE dp.document.id = :documentId
              AND dp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND dp.canAdmin = true
              AND (dp.expiresAt IS NULL OR dp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleAdminPermission(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // DEPARTMENT live — 사용자 부서 기반 문서 읽기 권한 존재 여부
    @Query("""
            SELECT COUNT(dp) > 0 FROM DocumentPermission dp
            JOIN User u ON u.department = dp.department
            WHERE dp.document.id = :documentId
              AND dp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND dp.canRead = true
              AND (dp.expiresAt IS NULL OR dp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptReadPermission(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // DEPARTMENT live — 사용자 부서 기반 문서 쓰기 권한 존재 여부
    @Query("""
            SELECT COUNT(dp) > 0 FROM DocumentPermission dp
            JOIN User u ON u.department = dp.department
            WHERE dp.document.id = :documentId
              AND dp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND dp.canWrite = true
              AND (dp.expiresAt IS NULL OR dp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptWritePermission(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // DEPARTMENT live — 사용자 부서 기반 문서 관리 권한 존재 여부
    @Query("""
            SELECT COUNT(dp) > 0 FROM DocumentPermission dp
            JOIN User u ON u.department = dp.department
            WHERE dp.document.id = :documentId
              AND dp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND dp.canAdmin = true
              AND (dp.expiresAt IS NULL OR dp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptAdminPermission(@Param("userId") Long userId, @Param("documentId") Long documentId);
}
