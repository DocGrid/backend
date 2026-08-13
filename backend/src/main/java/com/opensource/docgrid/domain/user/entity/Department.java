package com.opensource.docgrid.domain.user.entity;

import com.opensource.docgrid.domain.user.enums.CommonStatus;
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
 * 부서 테이블.
 *
 * <p>역할: 조직 계층 구조(부서 트리)를 표현한다.
 * 이유: 문서/컬렉션의 DEPARTMENT 단위 공개 범위(visibility) 및 부서 기반 권한 판단의 기준이 된다.
 * 관계: users.department_id가 이 테이블을 참조하며, self-FK(parent_department_id)로 상위 부서를 표현한다.
 * unique 제약: code는 부서 코드로 유일해야 한다(uk_departments_code).
 * index: parent_department_id, status에 대한 조회 인덱스를 둔다.
 *
 * <p>주의사항: 부서 기반 권한(예: collection_permissions/document_permissions의 DEPARTMENT 타입)은
 * user_document_access_cache에 미리 저장(materialize)하지 않는다. 검색 시점에
 * users.department_id + 권한 테이블을 조인하는 live predicate로 판단해야 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "departments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_departments_code", columnNames = "code")
        },
        indexes = {
                @Index(name = "idx_departments_parent_department_id", columnList = "parent_department_id"),
                @Index(name = "idx_departments_status", columnList = "status")
        }
)
public class Department extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 상위 부서 self-FK, 최상위 부서는 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_department_id")
    private Department parentDepartment;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommonStatus status;

    @Builder
    public Department(Department parentDepartment, String name, String code, String description, CommonStatus status) {
        this.parentDepartment = parentDepartment;
        this.name = name;
        this.code = code;
        this.description = description;
        this.status = status;
    }
}
