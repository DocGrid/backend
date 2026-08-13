package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.config.SyncDispatcherProperties;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 장시간 실행되는 Sync Handler가 현재 Claim Token을 증명하고 Lease만 연장할 수 있게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SyncEventLeaseService {

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final SyncDispatcherProperties syncDispatcherProperties;
    private final Clock clock;

    public LocalDateTime renew(UUID eventId, UUID claimToken) {
        LocalDateTime renewedAt = LocalDateTime.now(clock);
        SyncOutboxEvent event = syncOutboxEventRepository.findByEventIdForUpdate(eventId)
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_NOT_FOUND));
        LocalDateTime lockExpiresAt = renewedAt.plus(syncDispatcherProperties.getLeaseDuration());
        try {
            event.renewLease(claimToken, renewedAt, lockExpiresAt);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_OWNERSHIP_INVALID, exception);
        }
        return lockExpiresAt;
    }
}
