package com.opensource.docgrid.domain.collection.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.collection.entity.CollectionDocument;

public interface CollectionDocumentRepository extends JpaRepository<CollectionDocument, Long> {

    boolean existsByCollectionIdAndDocumentId(Long collectionId, Long documentId);

    List<CollectionDocument> findAllByCollectionId(Long collectionId);

    /**
     * 권한 선필터를 통과한 컬렉션 문서를 현재 버전 Metadata와 함께 페이지 조회한다.
     */
    @Query(
            value = """
                    SELECT cd
                    FROM CollectionDocument cd
                    JOIN FETCH cd.collection
                    JOIN FETCH cd.document d
                    JOIN FETCH d.owner
                    LEFT JOIN FETCH d.currentVersion
                    LEFT JOIN FETCH cd.addedBy
                    WHERE cd.collection.id = :collectionId
                      AND d.id IN :documentIds
                    """,
            countQuery = """
                    SELECT COUNT(cd)
                    FROM CollectionDocument cd
                    WHERE cd.collection.id = :collectionId
                      AND cd.document.id IN :documentIds
                    """
    )
    Page<CollectionDocument> findReadableDocuments(
            @Param("collectionId") Long collectionId,
            @Param("documentIds") List<Long> documentIds,
            Pageable pageable
    );

    // 컬렉션-문서 연결 단건 조회 (문서 제거용)
    Optional<CollectionDocument> findByCollectionIdAndDocumentId(Long collectionId, Long documentId);
}
