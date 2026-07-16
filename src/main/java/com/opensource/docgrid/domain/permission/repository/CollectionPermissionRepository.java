package com.opensource.docgrid.domain.permission.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.permission.entity.CollectionPermission;

public interface CollectionPermissionRepository extends JpaRepository<CollectionPermission, Long> {

    // ROLE live — 사용자 역할 기반 컬렉션→문서 읽기 권한 존재 여부
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN CollectionDocument cd ON cd.collection = cp.collection
            JOIN UserRole ur ON ur.role = cp.role
            WHERE cd.document.id = :documentId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND cp.canRead = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleReadPermissionForDocument(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // ROLE live — 사용자 역할 기반 컬렉션→문서 쓰기 권한 존재 여부
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN CollectionDocument cd ON cd.collection = cp.collection
            JOIN UserRole ur ON ur.role = cp.role
            WHERE cd.document.id = :documentId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND cp.canWrite = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleWritePermissionForDocument(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // ROLE live — 사용자 역할 기반 컬렉션→문서 관리 권한 존재 여부
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN CollectionDocument cd ON cd.collection = cp.collection
            JOIN UserRole ur ON ur.role = cp.role
            WHERE cd.document.id = :documentId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND cp.canAdmin = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleAdminPermissionForDocument(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // DEPARTMENT live — 사용자 부서 기반 컬렉션→문서 읽기 권한 존재 여부
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN CollectionDocument cd ON cd.collection = cp.collection
            JOIN User u ON u.department = cp.department
            WHERE cd.document.id = :documentId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND cp.canRead = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptReadPermissionForDocument(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // DEPARTMENT live — 사용자 부서 기반 컬렉션→문서 쓰기 권한 존재 여부
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN CollectionDocument cd ON cd.collection = cp.collection
            JOIN User u ON u.department = cp.department
            WHERE cd.document.id = :documentId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND cp.canWrite = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptWritePermissionForDocument(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // DEPARTMENT live — 사용자 부서 기반 컬렉션→문서 관리 권한 존재 여부
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN CollectionDocument cd ON cd.collection = cp.collection
            JOIN User u ON u.department = cp.department
            WHERE cd.document.id = :documentId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND cp.canAdmin = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptAdminPermissionForDocument(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // USER 직접 권한 — 컬렉션에 쓰기 권한이 있는지 (canAdminCollection 판단용)
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            WHERE cp.collection.id = :collectionId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.USER
              AND cp.user.id = :userId
              AND cp.canWrite = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsUserWritePermission(@Param("userId") Long userId, @Param("collectionId") Long collectionId);

    // USER 직접 권한 — 컬렉션에 관리 권한이 있는지 (canAdminCollection 판단용)
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            WHERE cp.collection.id = :collectionId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.USER
              AND cp.user.id = :userId
              AND cp.canAdmin = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsUserAdminPermission(@Param("userId") Long userId, @Param("collectionId") Long collectionId);
}
