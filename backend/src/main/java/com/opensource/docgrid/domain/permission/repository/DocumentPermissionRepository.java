package com.opensource.docgrid.domain.permission.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.permission.entity.DocumentPermission;

public interface DocumentPermissionRepository extends JpaRepository<DocumentPermission, Long> {

    /**
     * 문서에 직접 부여된 권한을 대상·부여자 정보와 함께 최신순으로 조회한다.
     */
    @Query("""
            SELECT dp
            FROM DocumentPermission dp
            JOIN FETCH dp.document
            LEFT JOIN FETCH dp.user
            LEFT JOIN FETCH dp.role
            LEFT JOIN FETCH dp.department
            LEFT JOIN FETCH dp.grantedBy
            WHERE dp.document.id = :documentId
            ORDER BY dp.grantedAt DESC, dp.id DESC
            """)
    List<DocumentPermission> findAllWithTargetsByDocumentId(@Param("documentId") Long documentId);

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
