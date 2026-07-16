package com.opensource.docgrid.domain.collection.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.collection.entity.CollectionDocument;

public interface CollectionDocumentRepository extends JpaRepository<CollectionDocument, Long> {

    boolean existsByCollectionIdAndDocumentId(Long collectionId, Long documentId);

    List<CollectionDocument> findAllByCollectionId(Long collectionId);

    // 컬렉션-문서 연결 단건 조회 (문서 제거용)
    Optional<CollectionDocument> findByCollectionIdAndDocumentId(Long collectionId, Long documentId);
}
