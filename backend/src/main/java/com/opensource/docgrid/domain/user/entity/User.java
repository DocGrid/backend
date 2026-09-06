package com.opensource.docgrid.domain.user.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.user.enums.UserStatus;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 테이블.
 *
 * <p>역할: 시스템의 모든 권한 판단은 항상 User.id에서 시작한다.
 * 이유: 로그인/소유권/권한 캐시(user_document_access_cache)의 기준 주체가 된다.
 * 관계: department_id로 Department를 참조(nullable), user_roles를 통해 Role과 N:M 관계.
 * unique 제약: email은 로그인 식별자로 유일해야 한다(uk_users_email).
 * index: department_id는 부서 기반 권한 검색에 사용, status는 활성 사용자 조회에 사용.
 *
 * <p>주의사항: 삭제는 물리 삭제보다 deleted_at 기반 soft delete를 우선 고려해야 한다.
 * (documents, collections와 동일하게 이 엔티티도 자체 deletedAt 필드를 가진다.)
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_email", columnNames = "email")
        },
        indexes = {
                @Index(name = "idx_users_department_id", columnList = "department_id"),
                @Index(name = "idx_users_status", columnList = "status")
        }
)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 소속 부서, 부서 기반 권한 검색(live predicate)의 기준. 미배정일 수 있으므로 nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String nickname;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // soft delete 시각, null이면 삭제되지 않은 상태
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public User(Department department, String email, String passwordHash, String name, String nickname,
                String profileImageUrl, UserStatus status) {
        this.department = department;
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.status = status != null ? status : UserStatus.ACTIVE;
    }

    /**
     * 인증에 성공한 시각을 마지막 로그인 기록으로 갱신한다.
     */
    public void recordLogin(LocalDateTime loginAt) {
        this.lastLoginAt = loginAt;
    }

    /**
     * 사용자의 소속 부서를 변경하거나 {@code null}로 미배정 상태로 전환한다.
     */
    public void changeDepartment(Department department) {
        this.department = department;
    }

    /**
     * 사용자를 논리 삭제 상태로 전환하고 삭제 시각을 함께 기록한다.
     *
     * <p>상태와 삭제 시각을 한 메서드에서 변경해 {@code DELETED}인데 삭제 시각이 없는 상태를 방지한다.
     */
    public void markDeleted(LocalDateTime deletedAt) {
        this.status = UserStatus.DELETED;
        this.deletedAt = deletedAt;
    }
}
