package com.opensource.docgrid.domain.permission.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.permission.entity.DocumentPermission;

public interface DocumentPermissionRepository extends JpaRepository<DocumentPermission, Long> {

    /**
     * 그룹 1 — 기본 조회 (1개)
     */

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

    /**
     * 그룹 2 — ROLE/DEPARTMENT live 체크 (ROLE 3개 + DEPT 3개 = 6개)
     *
     * <p>문서 자체에 직접 걸린 예외 권한(document_permissions)만 확인한다. 중간 조인 테이블 없이
     * dp.document.id로 바로 필터링 — CollectionPermissionRepository ①(컬렉션 경유)과 다름.
     * USER 대상은 없음(캐시로 판단), 조상 리스트 버전도 없음(문서는 트리 구조가 아님).
     */

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

    /**
     * 그룹 3 — 중복 부여 방지용 (3개)
     *
     * <p>이 문서에 이 대상(user/role/department) 앞으로 만료되지 않은 권한이 이미 있는지 확인한다.
     * permissionType(READ/WRITE/ADMIN)은 보지 않는다 — CollectionPermissionRepository와 동일한 이유
     * (계층적 구조상 같은 대상이 레벨만 다른 권한 행을 동시에 가질 이유가 없음). 만료된 권한은
     * 중복으로 치지 않아 재부여를 막지 않는다.
     */

    @Query("""
            SELECT COUNT(dp) > 0 FROM DocumentPermission dp
            WHERE dp.document.id = :documentId
              AND dp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.USER
              AND dp.user.id = :userId
              AND (dp.expiresAt IS NULL OR dp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsActiveUserGrant(@Param("documentId") Long documentId, @Param("userId") Long userId);

    @Query("""
            SELECT COUNT(dp) > 0 FROM DocumentPermission dp
            WHERE dp.document.id = :documentId
              AND dp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND dp.role.id = :roleId
              AND (dp.expiresAt IS NULL OR dp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsActiveRoleGrant(@Param("documentId") Long documentId, @Param("roleId") Long roleId);

    @Query("""
            SELECT COUNT(dp) > 0 FROM DocumentPermission dp
            WHERE dp.document.id = :documentId
              AND dp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND dp.department.id = :departmentId
              AND (dp.expiresAt IS NULL OR dp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsActiveDeptGrant(@Param("documentId") Long documentId, @Param("departmentId") Long departmentId);
}
