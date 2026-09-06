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

    /**
     * 현재 Claim Token이 유효한 PROCESSING Event의 Lease를 서버 설정 기간만큼 연장한다.
     *
     * @return 갱신된 Lease 만료 시각
     */
    public LocalDateTime renew(UUID eventId, UUID claimToken) {
        // 1. 잠금 획득 뒤 검증과 새 만료 시각 계산에 사용할 기준 시각을 정한다.
        LocalDateTime renewedAt = LocalDateTime.now(clock);

        // 2. Event 행을 잠가 성공·실패·복구와 Lease 갱신을 직렬화한다.
        SyncOutboxEvent event = syncOutboxEventRepository.findByEventIdForUpdate(eventId)
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_NOT_FOUND));

        // 3. 서버 설정 기간을 적용하되 Entity가 현재 Token과 기존 Lease 유효성을 최종 검증한다.
        LocalDateTime lockExpiresAt = renewedAt.plus(syncDispatcherProperties.getLeaseDuration());
        try {
            event.renewLease(claimToken, renewedAt, lockExpiresAt);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            // 4. Entity 불변식 오류는 외부 계약의 단일 소유권 오류로 변환한다.
            throw new DocGridException(ErrorCode.SYNC_EVENT_OWNERSHIP_INVALID, exception);
        }

        // 5. 호출자가 다음 갱신 시점을 판단할 수 있도록 실제 저장될 만료 시각을 반환한다.
        return lockExpiresAt;
    }
}
