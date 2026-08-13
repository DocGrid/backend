package com.opensource.docgrid.domain.sync.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;

/**
 * 동기화 Outbox Event의 영속성과 멱등 식별자 조회를 담당한다.
 *
 * <p>Dispatcher의 Claim·Lease Query는 해당 동작을 구현하는 단계에서 이 Repository에 추가한다.
 */
public interface SyncOutboxEventRepository extends JpaRepository<SyncOutboxEvent, Long> {

    Optional<SyncOutboxEvent> findByEventId(UUID eventId);

    Optional<SyncOutboxEvent> findByIdempotencyKey(String idempotencyKey);
}
