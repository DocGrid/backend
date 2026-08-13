package com.opensource.docgrid.domain.sync.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;

/**
 * 동기화 Outbox Event의 영속성과 멱등 식별자 조회를 담당한다.
 *
 * <p>일반 멱등 조회와 함께 PostgreSQL의 {@code FOR UPDATE SKIP LOCKED}를 이용해 Dispatcher Claim과
 * 만료 Lease 복구 경쟁을 직렬화한다.
 */
public interface SyncOutboxEventRepository extends JpaRepository<SyncOutboxEvent, Long> {

    long countByStatus(SyncEventStatus status);

    long countByStatusAndProcessedAtGreaterThanEqual(SyncEventStatus status, LocalDateTime since);

    long countByStatusAndUpdatedAtGreaterThanEqual(SyncEventStatus status, LocalDateTime since);

    long countByUpdatedAtGreaterThanEqualAndRetryCountGreaterThan(LocalDateTime since, int retryCount);

    @Query("SELECT MIN(event.occurredAt) FROM SyncOutboxEvent event WHERE event.status = :status")
    Optional<LocalDateTime> findOldestOccurredAtByStatus(@Param("status") SyncEventStatus status);

    Optional<SyncOutboxEvent> findTopByStatusOrderByProcessedAtDescIdDesc(SyncEventStatus status);

    @Query("""
        SELECT event
          FROM SyncOutboxEvent event
         WHERE (:status IS NULL OR event.status = :status)
           AND (:eventType IS NULL OR event.eventType = :eventType)
        """)
    Page<SyncOutboxEvent> findAdminEvents(
        @Param("status") SyncEventStatus status,
        @Param("eventType") SyncEventType eventType,
        Pageable pageable
    );

    Optional<SyncOutboxEvent> findByEventId(UUID eventId);

    Optional<SyncOutboxEvent> findByIdempotencyKey(String idempotencyKey);

    /**
     * 현재 실행 가능한 가장 오래된 PENDING Event 한 건을 다른 Dispatcher의 잠금을 기다리지 않고 Claim한다.
     */
    @Query(value = """
        SELECT event.*
        FROM sync_outbox_events event
        WHERE event.status = 'PENDING'
          AND event.available_at <= :claimedAt
        ORDER BY event.available_at ASC, event.occurred_at ASC, event.id ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<SyncOutboxEvent> findNextPendingForUpdate(@Param("claimedAt") LocalDateTime claimedAt);

    /**
     * Handler 실행·완료 전이와 다른 소유권 변경을 Event 행에서 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT event FROM SyncOutboxEvent event WHERE event.eventId = :eventId")
    Optional<SyncOutboxEvent> findByEventIdForUpdate(@Param("eventId") UUID eventId);

    /**
     * 만료된 PROCESSING Event 식별자를 오래 만료된 순서대로 제한 조회한다.
     */
    @Query(value = """
        SELECT event.event_id
        FROM sync_outbox_events event
        WHERE event.status = 'PROCESSING'
          AND event.lock_expires_at IS NOT NULL
          AND event.lock_expires_at <= :recoveredAt
        ORDER BY event.lock_expires_at ASC, event.id ASC
        LIMIT :batchSize
        """, nativeQuery = true)
    List<UUID> findExpiredProcessingEventIds(
        @Param("recoveredAt") LocalDateTime recoveredAt,
        @Param("batchSize") int batchSize
    );

    /**
     * 후보 Event가 여전히 만료 상태일 때만 복구 Transaction의 쓰기 잠금을 획득한다.
     */
    @Query(value = """
        SELECT event.*
        FROM sync_outbox_events event
        WHERE event.event_id = :eventId
          AND event.status = 'PROCESSING'
          AND event.lock_expires_at IS NOT NULL
          AND event.lock_expires_at <= :recoveredAt
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<SyncOutboxEvent> findExpiredByEventIdForUpdateSkipLocked(
        @Param("eventId") UUID eventId,
        @Param("recoveredAt") LocalDateTime recoveredAt
    );
}
