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

    public void retry(UUID eventId) {
        SyncOutboxEvent event = syncOutboxEventRepository.findByEventIdForUpdate(eventId)
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_NOT_FOUND));
        if (event.getStatus() != SyncEventStatus.FAILED) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_RETRY_NOT_ALLOWED);
        }
        event.requeueFailed(LocalDateTime.now(clock));
    }
}
