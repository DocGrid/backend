package com.opensource.docgrid.domain.embedding.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.embedding.entity.Embedding;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;

/**
 * 문서 Chunk Embedding Set의 영속화와 인덱싱 완료 검증·상태 전환을 담당한다.
 *
 * <p>생성 Transaction은 Version·Model 단위 저장 개수로 부분 저장을 구분한다. 완료 Transaction은
 * Vector를 Java Heap으로 역직렬화하지 않고 DB 집계로 관계·차원·Hash 불변식을 검증하고,
 * 이전 현재 Version 또는 최종 실패한 Version의 ACTIVE Set을 STALE로 일괄 전환한다.
 */
public interface EmbeddingRepository extends JpaRepository<Embedding, Long> {

    long countByDocumentVersionId(Long documentVersionId);

    long countByDocumentVersionIdAndEmbeddingModelId(
        Long documentVersionId,
        Long embeddingModelId
    );

    long countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
        Long documentVersionId,
        Long embeddingModelId,
        EmbeddingStatus status
    );

    /**
     * 인덱싱 완료 시 이전 현재 Version 또는 최종 실패 대상의 검색 가능한 Embedding을 한 SQL로 비활성화한다.
     *
     * @return 실제 STALE로 변경된 행 수
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        UPDATE embeddings
        SET status = 'STALE',
            updated_at = CURRENT_TIMESTAMP
        WHERE document_version_id = :documentVersionId
          AND status = 'ACTIVE'
        """, nativeQuery = true)
    int markActiveAsStaleByDocumentVersionId(
        @Param("documentVersionId") Long documentVersionId
    );

    /**
     * 대상 Chunk Set과 연결됐거나 대상 Version으로 역정규화된 Model 행 중 완료 불변식 위반 수를 계산한다.
     *
     * <p>양쪽 범위를 함께 조회해야 잘못된 역정규화 Version ID로 누락된 행과 다른 Chunk를 대상 Version으로
     * 잘못 표시한 행을 모두 잡을 수 있다.
     */
    @Query(value = """
        SELECT COUNT(*)
        FROM embeddings embedding
        JOIN document_chunks chunk ON chunk.id = embedding.chunk_id
        WHERE embedding.embedding_model_id = :embeddingModelId
          AND (
              embedding.document_version_id = :documentVersionId
              OR chunk.document_version_id = :documentVersionId
          )
          AND (
              embedding.document_id <> :documentId
              OR embedding.document_version_id <> :documentVersionId
              OR chunk.document_version_id <> :documentVersionId
              OR embedding.dimension <> :dimension
              OR vector_dims(embedding.vector) <> :dimension
              OR embedding.vector_hash IS NULL
              OR embedding.vector_hash !~ '^[0-9a-f]{64}$'
          )
        """, nativeQuery = true)
    long countInvalidCompletionRows(
        @Param("documentId") Long documentId,
        @Param("documentVersionId") Long documentVersionId,
        @Param("embeddingModelId") Long embeddingModelId,
        @Param("dimension") int dimension
    );
}
