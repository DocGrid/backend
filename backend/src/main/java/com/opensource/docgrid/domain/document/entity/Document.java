package com.opensource.docgrid.domain.document.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
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
 * document_versions/file_objects에 있고, 이 테이블은 문서의 소유자·제목·설명·현재 버전·공개범위·상태를 관리한다.
 * 이유: 문서는 여러 버전을 가질 수 있으며, 검색 대상은 항상 "현재 버전(current_version)"이어야 하기 때문이다.
 * 관계: owner -> User(소유자, not null), current_version_id -> DocumentVersion(nullable, 순환 FK).
 *
 * <p>circular FK 주의: documents와 document_versions는 서로를 참조하는 순환 구조다.
 * documents.current_version_id는 document_versions.id를 참조하고,
 * document_versions.document_id는 documents.id를 참조한다.
 * 따라서 문서 생성 시 current_version_id는 반드시 nullable이어야 하며, 아래 순서로 생성된다:
 * <pre>
 * 1. file_objects insert 또는 기존 file_objects 재사용
 * 2. documents insert (current_version_id = null)
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

    // 현재 버전 포인터. 최초 업로드 중에는 처리 대상을, 완료 후에는 검색 가능한 최신 Version을 가리킨다.
    // documents <-> document_versions 순환 FK이므로 반드시 nullable이다.
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

    /**
     * 문서 원장의 소유자·표시 Metadata·형식·공개 범위와 초기 상태를 생성한다.
     *
     * <p>최초 저장 전에는 순환 FK 때문에 currentVersion이 비어 있을 수 있으며, 버전 저장 후
     * {@link #updateCurrentVersion(DocumentVersion)}으로 연결한다.
     */
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

    /**
     * 최초 업로드 중 생성한 버전 또는 내부 복구 흐름의 대상 버전을 현재 포인터로 설정한다.
     *
     * <p>검색 가능한 완료 승격에는 상태·소유 관계를 검증하는
     * {@link #activateIndexedVersion(DocumentVersion)}을 사용한다.
     */
    public void updateCurrentVersion(DocumentVersion currentVersion) {
        this.currentVersion = currentVersion;
    }

    /**
     * 논리 문서의 현재 표시 제목과 설명을 갱신한다.
     *
     * <p>버전 생성 당시의 제목 Snapshot은 감사 이력이므로 변경하지 않는다.
     */
    public void updateMetadata(String title, String description) {
        this.title = title.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
    }

    /**
     * 문서의 공개 범위를 변경한다. 권한 검증과 접근 캐시 갱신은 호출 Service가 담당한다.
     */
    public void updateVisibility(VisibilityType visibility) {
        this.visibility = visibility;
    }

    /**
     * 같은 문서에 속하고 인덱싱을 마친 Version을 현재 검색 대상으로 활성화한다.
     *
     * <p>업로드 접수 단계의 포인터 설정은 {@link #updateCurrentVersion(DocumentVersion)}이 담당하고,
     * 이 메서드는 완료 Transaction 경계에서 Version 포인터와 문서 상태를 함께 변경한다.
     *
     * @param documentVersion 새 검색 대상이 될 완료 Version
     */
    public void activateIndexedVersion(DocumentVersion documentVersion) {
        // 1. 영속 식별자를 기준으로 다른 문서의 Version이 연결되는 것을 차단한다.
        if (id == null
            || documentVersion == null
            || documentVersion.getDocument() == null
            || documentVersion.getDocument().getId() == null
            || !id.equals(documentVersion.getDocument().getId())) {
            throw new IllegalArgumentException("현재 문서에 속한 Version만 활성화할 수 있습니다.");
        }

        // 2. 검색 준비가 끝난 Version만 현재 포인터로 승격한다.
        if (documentVersion.getStatus() != DocumentVersionStatus.INDEXED) {
            throw new IllegalStateException("INDEXED 상태의 문서 버전만 활성화할 수 있습니다.");
        }

        // 3. 포인터와 문서 상태를 함께 변경해 검색 조건이 중간 상태를 관찰하지 않게 한다.
        this.currentVersion = documentVersion;
        this.status = DocumentStatus.INDEXED;
    }

    /**
     * 검색 가능한 버전이 아직 없는 문서를 비동기 인덱싱 진행 상태로 표시한다.
     */
    public void markIndexing() {
        this.status = DocumentStatus.INDEXING;
    }

    /**
     * 실패 문서에 새 버전이 접수됐음을 나타내도록 업로드 상태로 되돌린다.
     */
    public void markUploaded() {
        this.status = DocumentStatus.UPLOADED;
    }

    /**
     * 문서에 검색 가능한 버전이 있음을 표시한다.
     *
     * <p>새 currentVersion과 함께 변경해야 하는 완료 경로에서는
     * {@link #activateIndexedVersion(DocumentVersion)}을 사용한다.
     */
    public void markIndexed() {
        this.status = DocumentStatus.INDEXED;
    }

    /**
     * 검색 가능한 버전 없이 인덱싱이 최종 실패한 문서를 실패 상태로 표시한다.
     */
    public void markFailed() {
        this.status = DocumentStatus.FAILED;
    }

    /**
     * 문서를 Soft-delete하고 삭제 시각을 함께 기록한다.
     *
     * <p>Vector 비활성화와 권한 캐시 정리는 호출 Transaction의 Outbox Event가 후속 처리한다.
     */
    public void markDeleted(LocalDateTime deletedAt) {
        this.status = DocumentStatus.DELETED;
        this.deletedAt = deletedAt;
    }
}
