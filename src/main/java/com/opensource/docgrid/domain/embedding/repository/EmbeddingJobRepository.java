package com.opensource.docgrid.domain.embedding.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;

public interface EmbeddingJobRepository extends JpaRepository<EmbeddingJob, Long> {
}
