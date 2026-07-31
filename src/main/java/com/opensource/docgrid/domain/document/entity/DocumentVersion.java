package com.opensource.docgrid.domain.document.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문서 버전 테이블.
 *
 * <p>역할: 문서의 특정 시점 스냅샷(파일/내용)을 표현한다. document_chunks/embeddings는 모두
 * 이 document_version 기준으로 생성된다.
 * 이유: 문서 수정 시 기존 chunk/embedding을 고치지 않고 새 버전을 만들어 새 chunk/embedding을 생성한다.
 * 이는 RAG 응답의 출처(citation)를 정확한 시점의 내용으로 추적하기 위함이다.
 * 관계: document_id -> Document(not null), file_object_id -> FileObject, created_by -> User.
 * documents.current_version_id가 이 테이블의 특정 row를 가리키는 순환 FK 관계임을 Document.currentVersion에서 확인할 것.
 * unique 제약: 같은 문서 내에서 version_no는 유일해야 한다.
 * index: document_id, file_object_id, status에 대한 조회 인덱스.
 *
 * <p>주의사항: 파이프라인 상태는 UPLOADED -> PARSING -> CHUNKED -> EMBEDDING -> INDEXED 순으로 전이되며,
 * 실패 시 FAILED로 전이된다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "document_versions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_document_versions_document_id_version_no",
                        columnNames = {"document_id", "version_no"}
                )
        },
        indexes = {
                @Index(name = "idx_document_versions_document_id", columnList = "document_id"),
                @Index(name = "idx_document_versions_file_object_id", columnList = "file_object_id"),
                @Index(name = "idx_document_versions_status", columnList = "status")
        }
)
public class DocumentVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 버전이 속한 문서
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    // 이 버전이 사용하는 실제 파일 바이너리 위치
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_object_id")
    private FileObject fileObject;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "title_snapshot", length = 500)
    private String titleSnapshot;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    @Column(name = "file_hash", length = 128)
    private String fileHash;

    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Column(name = "content_type", length = 200)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentVersionStatus status;

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    // 이 버전을 생성한 사용자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Builder
    public DocumentVersion(Document document, FileObject fileObject, int versionNo, String titleSnapshot,
                            String contentHash, String fileHash, String originalFilename, String contentType,
                            DocumentVersionStatus status, User createdBy) {
        this.document = document;
        this.fileObject = fileObject;
        this.versionNo = versionNo;
        this.titleSnapshot = titleSnapshot;
        this.contentHash = contentHash;
        this.fileHash = fileHash;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.status = status != null ? status : DocumentVersionStatus.UPLOADED;
        this.createdBy = createdBy;
    }

    public void markParsing() {
        // UPLOADED에서 시작한 최초 파싱만 허용하고 재개 여부 판단은 Command Service가 담당한다.
        if (status != DocumentVersionStatus.UPLOADED) {
            throw new IllegalStateException("UPLOADED 상태의 문서 버전만 PARSING으로 전환할 수 있습니다.");
        }
        this.status = DocumentVersionStatus.PARSING;
    }

    public void markChunked() {
        // Chunk Set 저장과 같은 Transaction에서 PARSING Version만 완료 상태로 전환한다.
        if (status != DocumentVersionStatus.PARSING) {
            throw new IllegalStateException("PARSING 상태의 문서 버전만 CHUNKED로 전환할 수 있습니다.");
        }
        this.status = DocumentVersionStatus.CHUNKED;
    }

    public void markEmbedding() {
        // Chunk Set이 확정된 Version만 Embedding 생성 단계에 진입할 수 있다.
        if (status != DocumentVersionStatus.CHUNKED) {
            throw new IllegalStateException("CHUNKED 상태의 문서 버전만 EMBEDDING으로 전환할 수 있습니다.");
        }
        this.status = DocumentVersionStatus.EMBEDDING;
    }

    public void markIndexed(LocalDateTime indexedAt) {
        this.status = DocumentVersionStatus.INDEXED;
        this.indexedAt = indexedAt;
    }

    public void markFailed() {
        this.status = DocumentVersionStatus.FAILED;
    }
}
