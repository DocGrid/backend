package com.opensource.docgrid.domain.collection.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.collection.entity.CollectionDocument;

public interface CollectionDocumentRepository extends JpaRepository<CollectionDocument, Long> {

    boolean existsByCollectionIdAndDocumentId(Long collectionId, Long documentId);
}
