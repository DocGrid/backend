package com.opensource.docgrid.domain.rag.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.rag.entity.RagResponse;

public interface RagResponseRepository extends JpaRepository<RagResponse, Long> {
}
