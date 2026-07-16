package com.opensource.docgrid.domain.document.repository;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    boolean existsByDocumentIdAndStatusIn(Long documentId, Collection<DocumentVersionStatus> statuses);

    Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNoDesc(Long documentId);

    @Query("SELECT COALESCE(MAX(dv.versionNo), 0) FROM DocumentVersion dv WHERE dv.document.id = :documentId")
    int findMaxVersionNo(@Param("documentId") Long documentId);
}
