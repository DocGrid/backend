package com.opensource.docgrid.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {
}
