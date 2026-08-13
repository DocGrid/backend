package com.opensource.docgrid.domain.permission.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.entity.Document;
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
 * 문서 단위 예외 권한 테이블.
 *
 * <p>역할: 특정 문서 하나에 대해서만 적용되는 예외적인 권한을 부여한다.
 * 이유: 기본 권한은 collection_permissions로 처리하되, 특정 문서만 다른 권한을 줘야 하는
 * 예외 케이스를 위해 document_permissions를 별도로 둔다.
 * 관계: document_id -> Document(not null), target_type에 따라 user_id/department_id/role_id 중
 * 하나만 채워진다. granted_by -> User(부여자).
 * index: document_id, (target_type, user_id), (target_type, department_id), (target_type, role_id), expires_at.
 *
 * <p>중요: CollectionPermission과 동일하게, target_type=USER면 user_id만, DEPARTMENT면 department_id만,
 * ROLE이면 role_id만 채워져야 한다는 개념적 CHECK 제약이 있으나 JPA에서는 강제할 수 없다.
 * TODO: DB migration에서 CHECK 제약 추가 필요 (target_type별 단일 FK만 NOT NULL).
 *
 * <p>주의사항: USER 대상 권한만 user_document_access_cache에 캐시 가능하고,
 * ROLE/DEPARTMENT 대상은 live predicate로 판단해야 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "document_permissions",
        indexes = {
                @Index(name = "idx_document_permissions_document_id", columnList = "document_id"),
                @Index(name = "idx_document_permissions_target_type_user_id", columnList = "target_type, user_id"),
                @Index(name = "idx_document_permissions_target_type_department_id", columnList = "target_type, department_id"),
                @Index(name = "idx_document_permissions_target_type_role_id", columnList = "target_type, role_id"),
                @Index(name = "idx_document_permissions_expires_at", columnList = "expires_at")
        }
)
public class DocumentPermission extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 권한이 적용되는 문서
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

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
    public DocumentPermission(Document document, PermissionTargetType targetType, User user, Department department,
                              Role role, PermissionType permissionType, boolean canRead, boolean canWrite,
                              boolean canAdmin, User grantedBy, LocalDateTime grantedAt, LocalDateTime expiresAt) {
        this.document = document;
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
