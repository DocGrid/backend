package com.opensource.docgrid.domain.sync.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.sync.entity.SyncEventDeliveryAttempt;

/**
 * Sync Event Claim 세대별 append-only 실행 이력의 저장과 현재 Attempt 잠금 조회를 담당한다.
 */
public interface SyncEventDeliveryAttemptRepository extends JpaRepository<SyncEventDeliveryAttempt, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT attempt
          FROM SyncEventDeliveryAttempt attempt
         WHERE attempt.eventId = :eventId
           AND attempt.claimToken = :claimToken
        """)
    Optional<SyncEventDeliveryAttempt> findByEventIdAndClaimTokenForUpdate(
        @Param("eventId") UUID eventId,
        @Param("claimToken") UUID claimToken
    );
}
