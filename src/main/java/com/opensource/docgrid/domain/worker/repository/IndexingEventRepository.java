package com.opensource.docgrid.domain.worker.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;

/**
 * Embedding Job의 인덱싱 상태 변경 이벤트를 append-only 방식으로 저장하고 완료 이벤트 수를 검증하는 Repository.
 *
 * <p>Job Claim에서는 PROCESSING 전환과 같은 Transaction 안에 LOCKED 이벤트를 남겨 상태 변경 원인을
 * 추적할 수 있게 한다.
 */
public interface IndexingEventRepository extends JpaRepository<IndexingEvent, Long> {

    /**
     * 지정 Job의 append-only Event를 호출자가 지정한 고정 정렬·Pagination으로 조회한다.
     */
    Page<IndexingEvent> findAllByEmbeddingJobId(Long embeddingJobId, Pageable pageable);

    long countByEmbeddingJobIdAndEventType(Long embeddingJobId, IndexingEventType eventType);
}
