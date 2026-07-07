package com.opensource.docgrid.domain.embedding.entity;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
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
 * 임베딩(벡터) 테이블.
 *
 * <p>역할: OpenSQL 기반 vector search의 핵심 테이블로, chunk 하나를 특정 모델로 벡터화한 결과를 저장한다.
 * 이유: 검색 시 이 테이블을 대상으로 vector similarity search를 수행한 뒤, 반드시
 * users/roles/departments/permissions/user_document_access_cache로 권한 필터링을 거쳐야 한다.
 * 관계: chunk_id -> DocumentChunk(not null), embedding_model_id -> EmbeddingModel.
 * document_id/document_version_id는 검색 성능을 위한 역정규화(denormalized) 필드로,
 * 각각 chunk.documentVersion.document.id / chunk.documentVersion.id 값과 항상 일치해야 한다.
 * unique 제약: (chunk_id, embedding_model_id) 조합은 유일해야 한다 — 같은 chunk를 같은 모델로 중복 임베딩 금지.
 * index: (embedding_model_id, status), document_id, document_version_id.
 *
 * <p>주의사항: vector 컬럼은 이 프로젝트에 아직 Hibernate vector 타입 매핑이 없어 TEXT로 임시 매핑했다.
 * TODO: 추후 OpenSQL vector 타입(예: vector(768/1024/1536))으로 반드시 교체해야 한다.
 * dimension은 vector 값의 차원 수 검증용으로 별도 저장하며 embedding_models.dimension과 일치해야 한다.
 * MVP는 단일 active/searchable 모델 + 고정 dimension을 전제로 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "embeddings",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_embeddings_chunk_id_embedding_model_id",
                        columnNames = {"chunk_id", "embedding_model_id"}
                )
        },
        indexes = {
                @Index(name = "idx_embeddings_embedding_model_id_status", columnList = "embedding_model_id, status"),
                @Index(name = "idx_embeddings_document_id", columnList = "document_id"),
                @Index(name = "idx_embeddings_document_version_id", columnList = "document_version_id")
        }
)
public class Embedding extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 임베딩의 원본 chunk
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chunk_id", nullable = false)
    private DocumentChunk chunk;

    // 검색 성능용 역정규화 필드: chunk.documentVersion.document와 값이 일치해야 함
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    // 검색 성능용 역정규화 필드: chunk.documentVersion과 값이 일치해야 함
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_version_id", nullable = false)
    private DocumentVersion documentVersion;

    // 이 벡터를 생성한 임베딩 모델
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "embedding_model_id", nullable = false)
    private EmbeddingModel embeddingModel;

    // TODO: 추후 OpenSQL vector 타입(예: vector(768/1024/1536))으로 교체 필요. 현재는 TEXT 임시 매핑.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String vector;

    @Column(nullable = false)
    private int dimension;

    @Column(name = "vector_hash", length = 128)
    private String vectorHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmbeddingStatus status;

    @Builder
    public Embedding(DocumentChunk chunk, Document document, DocumentVersion documentVersion,
                      EmbeddingModel embeddingModel, String vector, int dimension, String vectorHash,
                      EmbeddingStatus status) {
        this.chunk = chunk;
        this.document = document;
        this.documentVersion = documentVersion;
        this.embeddingModel = embeddingModel;
        this.vector = vector;
        this.dimension = dimension;
        this.vectorHash = vectorHash;
        this.status = status != null ? status : EmbeddingStatus.ACTIVE;
    }
}
