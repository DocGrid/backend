package com.opensource.docgrid.domain.document.entity;

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
 * 문서 청크(분할 조각) 테이블.
 *
 * <p>역할: RAG 출처(citation)의 최소 단위. 문서 버전의 본문을 검색/임베딩 가능한 단위로 분할한 결과다.
 * 이유: embeddings/search_results/response_citations가 모두 이 chunk를 기준으로 연결된다.
 * 관계: document_version_id -> DocumentVersion(not null). document_version에 종속된 불변 데이터다.
 * unique 제약: 같은 버전 내에서 chunk_index는 유일해야 한다.
 * index: document_version_id, page_no, content_hash에 대한 조회 인덱스.
 *
 * <p>주의사항: 이 엔티티는 상태(status) 필드를 두지 않는다 — 한번 생성되면 변경되지 않는 불변 데이터이기 때문이다.
 * 문서 내용이 수정되면 기존 chunk를 고치지 않고 새 document_version 아래에 새 chunk를 생성한다.
 * metadataJson은 현재 Flyway Schema와 동일하게 TEXT 컬럼으로 매핑하며, 구조 기반 조회가 필요해지면
 * PostgreSQL JSONB와 Hibernate JSON 매핑으로 함께 변경해야 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "document_chunks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_document_chunks_document_version_id_chunk_index",
                        columnNames = {"document_version_id", "chunk_index"}
                )
        },
        indexes = {
                @Index(name = "idx_document_chunks_document_version_id", columnList = "document_version_id"),
                @Index(name = "idx_document_chunks_page_no", columnList = "page_no"),
                @Index(name = "idx_document_chunks_content_hash", columnList = "content_hash")
        }
)
public class DocumentChunk extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 청크가 속한 문서 버전
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_version_id", nullable = false)
    private DocumentVersion documentVersion;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "char_start", nullable = false)
    private int charStart;

    @Column(name = "char_end", nullable = false)
    private int charEnd;

    // 원본 문서에서의 페이지 번호, 페이지 개념이 없는 포맷은 null
    @Column(name = "page_no")
    private Integer pageNo;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    // 현재 Schema가 TEXT이므로 문자열로 보존한다. JSONB 전환 시 Flyway와 Hibernate 매핑을 함께 바꿔야 한다.
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Builder
    public DocumentChunk(DocumentVersion documentVersion, int chunkIndex, String chunkText, int tokenCount,
                          int charStart, int charEnd, Integer pageNo, String sectionTitle, String contentHash,
                          String metadataJson) {
        this.documentVersion = documentVersion;
        this.chunkIndex = chunkIndex;
        this.chunkText = chunkText;
        this.tokenCount = tokenCount;
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.pageNo = pageNo;
        this.sectionTitle = sectionTitle;
        this.contentHash = contentHash;
        this.metadataJson = metadataJson;
    }
}
