package com.opensource.docgrid.domain.document.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.document.entity.Document;

// A담당자 영역 — B담당자는 존재 확인 등 읽기 전용으로만 사용
public interface DocumentRepository extends JpaRepository<Document, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :documentId")
    Optional<Document> findByIdForUpdate(@Param("documentId") Long documentId);
}
