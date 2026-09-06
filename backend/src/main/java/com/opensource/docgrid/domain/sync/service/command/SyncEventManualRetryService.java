package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자가 최종 실패한 Sync Event에 실행 기회 한 번을 추가하는 Command Service다.
 *
 * <p>관리 API는 후속 단계에서 이 Service를 호출하며, 이 경계는 처리 중인 Event의 소유권을 덮어쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SyncEventManualRetryService {

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final Clock clock;

    /**
     * 최종 FAILED Event를 즉시 Claim 가능한 PENDING 상태로 되돌린다.
     *
     * @param eventId 관리자가 재처리할 Outbox Event 식별자
     */
    public void retry(UUID eventId) {
        // 1. Event 행을 잠가 다른 관리자 재시도나 상태 전이와 경쟁하지 않게 한다.
        SyncOutboxEvent event = syncOutboxEventRepository.findByEventIdForUpdate(eventId)
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_NOT_FOUND));

        // 2. PROCESSING 소유권이나 자동 Retry 예약을 덮지 않도록 최종 FAILED 상태만 허용한다.
        if (event.getStatus() != SyncEventStatus.FAILED) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_RETRY_NOT_ALLOWED);
        }

        // 3. 과거 이력은 보존하고 최대 Retry 한도를 한 번 늘린 새 수동 실행 기회를 부여한다.
        event.requeueFailed(LocalDateTime.now(clock));
    }
}
