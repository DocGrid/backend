package com.opensource.docgrid.domain.collection.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
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
 * 문서 컬렉션(그룹/폴더/워크스페이스) 테이블.
 *
 * <p>역할: 문서를 그룹화하는 단위(폴더 또는 워크스페이스)를 표현한다.
 * 이유: 문서 단위 권한 관리는 번거로우므로 기본 권한 단위는 collection_permissions로 두고,
 * document_permissions는 예외적인 문서 단위 권한으로만 사용한다.
 * 관계: owner -> User, parent_collection_id -> self(컬렉션 트리), collection_documents를 통해 Document와 N:M.
 * 클래스명은 java.util.Collection과의 충돌을 피하기 위해 DocumentCollection으로 명명했으나 테이블명은 collections이다.
 * index: owner_user_id, parent_collection_id, visibility, status.
 *
 * <p>주의사항: 삭제는 deleted_at 기반 soft delete를 사용한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "collections",
        indexes = {
                @Index(name = "idx_collections_owner_user_id", columnList = "owner_user_id"),
                @Index(name = "idx_collections_parent_collection_id", columnList = "parent_collection_id"),
                @Index(name = "idx_collections_visibility", columnList = "visibility"),
                @Index(name = "idx_collections_status", columnList = "status")
        }
)
public class DocumentCollection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 컬렉션 소유자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    // 상위 컬렉션 self-FK, 최상위 컬렉션은 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_collection_id")
    private DocumentCollection parentCollection;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VisibilityType visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CollectionStatus status;

    // soft delete 시각, null이면 삭제되지 않은 상태
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public DocumentCollection(User owner, DocumentCollection parentCollection, String name, String description,
                               VisibilityType visibility, CollectionStatus status) {
        this.owner = owner;
        this.parentCollection = parentCollection;
        this.name = name;
        this.description = description;
        this.visibility = visibility;
        this.status = status != null ? status : CollectionStatus.ACTIVE;
    }

    public void markDeleted(LocalDateTime deletedAt) {
        this.status = CollectionStatus.DELETED;
        this.deletedAt = deletedAt;
    }
}
