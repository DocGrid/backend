package com.opensource.docgrid.domain.document.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
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
 * 문서(논리적 루트) 테이블.
 *
 * <p>역할: "파일"이 아니라 "문서"라는 논리적 개념을 저장한다. 실제 파일/버전 정보는
 * document_versions/file_objects에 있고, 이 테이블은 문서의 소유자·현재 버전·공개범위·상태만 관리한다.
 * 이유: 문서는 여러 버전을 가질 수 있으며, 검색 대상은 항상 "현재 버전(current_version)"이어야 하기 때문이다.
 * 관계: owner -> User(소유자, not null), current_version_id -> DocumentVersion(nullable, 순환 FK).
 *
 * <p>circular FK 주의: documents와 document_versions는 서로를 참조하는 순환 구조다.
 * documents.current_version_id는 document_versions.id를 참조하고,
 * document_versions.document_id는 documents.id를 참조한다.
 * 따라서 문서 생성 시 current_version_id는 반드시 nullable이어야 하며, 아래 순서로 생성된다:
 * <pre>
 * 1. documents insert (current_version_id = null)
 * 2. file_objects insert 또는 기존 file_objects 재사용
 * 3. document_versions insert (document_id = documents.id)
 * 4. documents.current_version_id update
 * 5. embedding_jobs insert
 * </pre>
 *
 * <p>index: owner_user_id, current_version_id, status, visibility, (status, visibility) 복합 인덱스.
 *
 * <p>주의사항: 검색은 항상 currentVersion이 가리키는 INDEXED 상태의 버전만 대상으로 해야 한다.
 * 삭제는 deleted_at 기반 soft delete를 사용한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "documents",
        indexes = {
                @Index(name = "idx_documents_owner_user_id", columnList = "owner_user_id"),
                @Index(name = "idx_documents_current_version_id", columnList = "current_version_id"),
                @Index(name = "idx_documents_status", columnList = "status"),
                @Index(name = "idx_documents_visibility", columnList = "visibility"),
                @Index(name = "idx_documents_status_visibility", columnList = "status, visibility")
        }
)
public class Document extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 문서 소유자, 권한 판단의 기준(OWNER access source)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    // 현재 활성 버전. documents <-> document_versions 순환 FK이므로 반드시 nullable.
    // 문서 생성 직후에는 null이며, 버전 색인 완료 후 updateCurrentVersion()으로 갱신된다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_version_id")
    private DocumentVersion currentVersion;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private DocumentSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VisibilityType visibility;

    // soft delete 시각, null이면 삭제되지 않은 상태
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public Document(User owner, DocumentVersion currentVersion, String title, String description,
                     DocumentType documentType, DocumentSourceType sourceType, DocumentStatus status,
                     VisibilityType visibility) {
        this.owner = owner;
        this.currentVersion = currentVersion;
        this.title = title;
        this.description = description;
        this.documentType = documentType;
        this.sourceType = sourceType;
        this.status = status != null ? status : DocumentStatus.DRAFT;
        this.visibility = visibility;
    }

    public void updateCurrentVersion(DocumentVersion currentVersion) {
        this.currentVersion = currentVersion;
    }

    public void markIndexing() {
        this.status = DocumentStatus.INDEXING;
    }

    public void markIndexed() {
        this.status = DocumentStatus.INDEXED;
    }

    public void markFailed() {
        this.status = DocumentStatus.FAILED;
    }

    public void markDeleted(LocalDateTime deletedAt) {
        this.status = DocumentStatus.DELETED;
        this.deletedAt = deletedAt;
    }
}
