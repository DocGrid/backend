package com.opensource.docgrid.domain.worker.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.worker.entity.IndexingEvent;

/**
 * Embedding Job의 인덱싱 상태 변경 이벤트를 append-only 방식으로 저장하는 Repository.
 *
 * <p>Job Claim에서는 PROCESSING 전환과 같은 Transaction 안에 LOCKED 이벤트를 남겨 상태 변경 원인을
 * 추적할 수 있게 한다.
 */
public interface IndexingEventRepository extends JpaRepository<IndexingEvent, Long> {
}
