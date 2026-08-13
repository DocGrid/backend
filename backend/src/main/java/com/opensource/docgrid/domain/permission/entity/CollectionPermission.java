package com.opensource.docgrid.domain.permission.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 컬렉션 단위 권한 테이블.
 *
 * <p>역할: 컬렉션(및 그 하위 문서들)에 대한 기본 권한을 부여한다.
 * 이유: 문서 하나하나에 권한을 주는 대신 컬렉션 단위로 묶어 관리 효율을 높인다.
 * 관계: collection_id -> DocumentCollection(not null), target_type에 따라 user_id/department_id/role_id 중
 * 하나만 채워진다. granted_by -> User(부여자).
 * index: collection_id, (target_type, user_id), (target_type, department_id), (target_type, role_id), expires_at.
 *
 * <p>중요: target_type=USER면 user_id만, DEPARTMENT면 department_id만, ROLE이면 role_id만 채워져야 한다는
 * 개념적 CHECK 제약이 있으나 JPA/애플리케이션 레벨에서는 강제할 수 없다.
 * TODO: DB migration에서 CHECK 제약 추가 필요 (target_type별 단일 FK만 NOT NULL).
 *
 * <p>주의사항: USER 대상 권한만 user_document_access_cache에 materialize(사전 계산)될 수 있다.
 * ROLE/DEPARTMENT 대상 권한은 캐시에 저장하지 않고 검색 시점에 live predicate로 판단해야 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "collection_permissions",
        indexes = {
                @Index(name = "idx_collection_permissions_collection_id", columnList = "collection_id"),
                @Index(name = "idx_collection_permissions_target_type_user_id", columnList = "target_type, user_id"),
                @Index(name = "idx_collection_permissions_target_type_department_id", columnList = "target_type, department_id"),
                @Index(name = "idx_collection_permissions_target_type_role_id", columnList = "target_type, role_id"),
                @Index(name = "idx_collection_permissions_expires_at", columnList = "expires_at")
        }
)
public class CollectionPermission extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 권한이 적용되는 컬렉션
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id", nullable = false)
    private DocumentCollection collection;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private PermissionTargetType targetType;

    // target_type=USER일 때만 값이 채워짐
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // target_type=DEPARTMENT일 때만 값이 채워짐
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    // target_type=ROLE일 때만 값이 채워짐
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_type", nullable = false, length = 20)
    private PermissionType permissionType;

    @Column(name = "can_read", nullable = false)
    private boolean canRead;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite;

    @Column(name = "can_admin", nullable = false)
    private boolean canAdmin;

    // 이 권한을 부여한 사용자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by")
    private User grantedBy;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    // 권한 만료 시각, null이면 만료 없음
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Builder
    public CollectionPermission(DocumentCollection collection, PermissionTargetType targetType, User user,
                                 Department department, Role role, PermissionType permissionType,
                                 boolean canRead, boolean canWrite, boolean canAdmin, User grantedBy,
                                 LocalDateTime grantedAt, LocalDateTime expiresAt) {
        this.collection = collection;
        this.targetType = targetType;
        this.user = user;
        this.department = department;
        this.role = role;
        this.permissionType = permissionType;
        this.canRead = canRead;
        this.canWrite = canWrite;
        this.canAdmin = canAdmin;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
    }
}
