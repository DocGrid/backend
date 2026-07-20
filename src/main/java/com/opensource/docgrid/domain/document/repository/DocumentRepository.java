package com.opensource.docgrid.domain.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

// A담당자 영역 — B담당자는 존재 확인 등 읽기 전용으로만 사용
public interface DocumentRepository extends JpaRepository<Document, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :documentId")
    Optional<Document> findByIdForUpdate(@Param("documentId") Long documentId);

    @Query("""
        SELECT d.id AS documentId,
               d.status AS documentStatus,
               cv.versionNo AS currentVersionNo,
               cv.status AS currentVersionStatus,
               pv.versionNo AS processingVersionNo,
               pv.status AS processingVersionStatus,
               ej.status AS processingJobStatus
        FROM Document d
        LEFT JOIN d.currentVersion cv
        LEFT JOIN DocumentVersion pv
               ON pv.document = d
              AND pv.status IN :processingVersionStatuses
        LEFT JOIN EmbeddingJob ej
               ON ej.documentVersion = pv
              AND ej.status IN :activeJobStatuses
        WHERE d.id = :documentId
        """)
    List<DocumentStatusProjection> findDocumentStatus(
        @Param("documentId") Long documentId,
        @Param("processingVersionStatuses") Collection<DocumentVersionStatus> processingVersionStatuses,
        @Param("activeJobStatuses") Collection<EmbeddingJobStatus> activeJobStatuses
    );
}
