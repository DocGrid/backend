package com.opensource.docgrid.domain.permission.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.permission.entity.CollectionPermission;

public interface CollectionPermissionRepository extends JpaRepository<CollectionPermission, Long> {

    // 컬렉션에 속한 권한 전체 조회 (soft delete 시 캐시 무효화 + 권한 삭제용)
    List<CollectionPermission> findAllByCollectionId(Long collectionId);

    // cascade 삭제용 — 대상 컬렉션 ID 목록(자기 자신+후손 전체)에 걸린 권한 전체 조회
    List<CollectionPermission> findAllByCollectionIdIn(List<Long> collectionIds);

    /**
     * 컬렉션에 직접 부여된 권한을 대상·부여자 정보와 함께 최신순으로 조회한다.
     */
    @Query("""
            SELECT cp
            FROM CollectionPermission cp
            JOIN FETCH cp.collection
            LEFT JOIN FETCH cp.user
            LEFT JOIN FETCH cp.role
            LEFT JOIN FETCH cp.department
            LEFT JOIN FETCH cp.grantedBy
            WHERE cp.collection.id = :collectionId
            ORDER BY cp.grantedAt DESC, cp.id DESC
            """)
    List<CollectionPermission> findAllWithTargetsByCollectionId(@Param("collectionId") Long collectionId);

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

    // USER 직접 권한 — 컬렉션에 읽기 권한이 있는지 (canReadCollection 판단용)
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            WHERE cp.collection.id = :collectionId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.USER
              AND cp.user.id = :userId
              AND cp.canRead = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsUserReadPermission(@Param("userId") Long userId, @Param("collectionId") Long collectionId);

    // USER 직접 권한 — 컬렉션에 쓰기 권한이 있는지 (canWriteCollection 판단용)
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

    // ROLE live — 사용자 역할 기반 컬렉션 읽기 권한 존재 여부 (canReadCollection 판단용)
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN UserRole ur ON ur.role = cp.role
            WHERE cp.collection.id = :collectionId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND cp.canRead = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleReadPermissionForCollection(@Param("userId") Long userId, @Param("collectionId") Long collectionId);

    // ROLE live — 사용자 역할 기반 컬렉션 쓰기 권한 존재 여부 (canWriteCollection 판단용)
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN UserRole ur ON ur.role = cp.role
            WHERE cp.collection.id = :collectionId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND cp.canWrite = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleWritePermissionForCollection(@Param("userId") Long userId, @Param("collectionId") Long collectionId);

    // ROLE live — 사용자 역할 기반 컬렉션 관리 권한 존재 여부 (canAdminCollection 판단용)
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN UserRole ur ON ur.role = cp.role
            WHERE cp.collection.id = :collectionId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND cp.canAdmin = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleAdminPermissionForCollection(@Param("userId") Long userId, @Param("collectionId") Long collectionId);

    // DEPARTMENT live — 사용자 부서 기반 컬렉션 읽기 권한 존재 여부 (canReadCollection 판단용)
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN User u ON u.department = cp.department
            WHERE cp.collection.id = :collectionId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND cp.canRead = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptReadPermissionForCollection(@Param("userId") Long userId, @Param("collectionId") Long collectionId);

    // DEPARTMENT live — 사용자 부서 기반 컬렉션 쓰기 권한 존재 여부 (canWriteCollection 판단용)
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN User u ON u.department = cp.department
            WHERE cp.collection.id = :collectionId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND cp.canWrite = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptWritePermissionForCollection(@Param("userId") Long userId, @Param("collectionId") Long collectionId);

    // DEPARTMENT live — 사용자 부서 기반 컬렉션 관리 권한 존재 여부 (canAdminCollection 판단용)
    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN User u ON u.department = cp.department
            WHERE cp.collection.id = :collectionId
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND cp.canAdmin = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptAdminPermissionForCollection(@Param("userId") Long userId, @Param("collectionId") Long collectionId);

    // 컬렉션 트리 상속용 — 컬렉션 ID 목록(자기 자신+조상 또는 문서가 속한 컬렉션+조상) 중
    // 하나라도 ROLE/DEPARTMENT 권한이 있으면 true. 기존 단일-ID 메서드는 그대로 두고 추가로 병행한다.

    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN UserRole ur ON ur.role = cp.role
            WHERE cp.collection.id IN :collectionIds
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND cp.canRead = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleReadPermissionForCollections(@Param("userId") Long userId, @Param("collectionIds") List<Long> collectionIds);

    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN UserRole ur ON ur.role = cp.role
            WHERE cp.collection.id IN :collectionIds
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND cp.canWrite = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleWritePermissionForCollections(@Param("userId") Long userId, @Param("collectionIds") List<Long> collectionIds);

    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN UserRole ur ON ur.role = cp.role
            WHERE cp.collection.id IN :collectionIds
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.ROLE
              AND ur.user.id = :userId
              AND cp.canAdmin = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsRoleAdminPermissionForCollections(@Param("userId") Long userId, @Param("collectionIds") List<Long> collectionIds);

    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN User u ON u.department = cp.department
            WHERE cp.collection.id IN :collectionIds
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND cp.canRead = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptReadPermissionForCollections(@Param("userId") Long userId, @Param("collectionIds") List<Long> collectionIds);

    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN User u ON u.department = cp.department
            WHERE cp.collection.id IN :collectionIds
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND cp.canWrite = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptWritePermissionForCollections(@Param("userId") Long userId, @Param("collectionIds") List<Long> collectionIds);

    @Query("""
            SELECT COUNT(cp) > 0 FROM CollectionPermission cp
            JOIN User u ON u.department = cp.department
            WHERE cp.collection.id IN :collectionIds
              AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.DEPARTMENT
              AND u.id = :userId
              AND cp.canAdmin = true
              AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsDeptAdminPermissionForCollections(@Param("userId") Long userId, @Param("collectionIds") List<Long> collectionIds);
}
