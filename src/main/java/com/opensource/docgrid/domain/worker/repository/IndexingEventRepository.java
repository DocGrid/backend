package com.opensource.docgrid.domain.worker.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.worker.entity.IndexingEvent;

public interface IndexingEventRepository extends JpaRepository<IndexingEvent, Long> {
}
