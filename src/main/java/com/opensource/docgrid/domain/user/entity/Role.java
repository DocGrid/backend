package com.opensource.docgrid.domain.user.entity;

import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 역할(권한 그룹) 테이블.
 *
 * <p>역할: ADMIN/USER/DOCUMENT_MANAGER 같은 역할(role)을 표현한다.
 * 이유: 역할 기반 권한 부여(collection_permissions/document_permissions의 ROLE 타입)의 기준이 된다.
 * 관계: user_roles를 통해 User와 N:M 관계를 맺는다.
 * unique 제약: code는 역할 코드로 유일해야 한다(uk_roles_code).
 *
 * <p>주의사항: Role 기반 권한은 user_document_access_cache에 저장하지 않는다.
 * 검색 시점에 user_roles를 live join하여 판단해야 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "roles",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_roles_code", columnNames = "code")
        }
)
public class Role extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder
    public Role(String name, String code, String description) {
        this.name = name;
        this.code = code;
        this.description = description;
    }
}
