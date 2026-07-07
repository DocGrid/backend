package com.opensource.docgrid.domain.collection.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.user.entity.User;
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
 * 컬렉션-문서 매핑 테이블.
 *
 * <p>역할: collections와 documents의 N:M 관계를 해소하는 중간 엔티티.
 * 이유: 같은 문서가 여러 컬렉션에 속할 수 있고, 하나의 컬렉션에 여러 문서가 속할 수 있다.
 * 관계: collection_id -> DocumentCollection, document_id -> Document, added_by -> User.
 * unique 제약: 같은 컬렉션에 같은 문서가 중복 추가되는 것을 금지한다.
 * index: collection_id, document_id.
 *
 * <p>주의사항: collection_permissions의 적용 범위(어떤 문서가 어떤 컬렉션 권한의 영향을 받는지) 계산에 필요하다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "collection_documents",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_collection_documents_collection_id_document_id",
                        columnNames = {"collection_id", "document_id"}
                )
        },
        indexes = {
                @Index(name = "idx_collection_documents_collection_id", columnList = "collection_id"),
                @Index(name = "idx_collection_documents_document_id", columnList = "document_id")
        }
)
public class CollectionDocument extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 문서가 속하는 컬렉션
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id", nullable = false)
    private DocumentCollection collection;

    // 컬렉션에 속하는 문서
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    // 이 문서를 컬렉션에 추가한 사용자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by")
    private User addedBy;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @Builder
    public CollectionDocument(DocumentCollection collection, Document document, User addedBy, LocalDateTime addedAt) {
        this.collection = collection;
        this.document = document;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }
}
