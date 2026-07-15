package com.opensource.docgrid.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.document.entity.Document;

// A담당자 영역 — B담당자는 존재 확인 등 읽기 전용으로만 사용
public interface DocumentRepository extends JpaRepository<Document, Long> {
}
