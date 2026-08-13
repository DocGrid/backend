package com.opensource.docgrid.domain.user.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자-역할 매핑 테이블.
 *
 * <p>역할: users와 roles의 N:M 관계를 해소하는 중간 엔티티.
 * 이유: 한 사용자가 여러 역할을 가질 수 있어야 한다.
 * 관계: user_id -> User, role_id -> Role, assigned_by -> User(부여자).
 * unique 제약: 같은 사용자에게 같은 role이 중복 부여되는 것을 금지한다(uk_user_roles_user_id_role_id).
 *
 * <p>주의사항: Role 기반 권한은 캐시에 저장하지 않고 이 테이블을 live join하여 판단한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "user_roles",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_roles_user_id_role_id", columnNames = {"user_id", "role_id"})
        },
        indexes = {
                @Index(name = "idx_user_roles_user_id", columnList = "user_id"),
                @Index(name = "idx_user_roles_role_id", columnList = "role_id")
        }
)
public class UserRole extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 역할이 부여된 사용자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 부여된 역할
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    // 이 역할을 부여한 사용자(관리자 등)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private User assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Builder
    public UserRole(User user, Role role, User assignedBy, LocalDateTime assignedAt) {
        this.user = user;
        this.role = role;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
    }
}
