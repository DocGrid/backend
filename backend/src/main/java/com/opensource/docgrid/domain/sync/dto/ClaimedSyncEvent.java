package com.opensource.docgrid.domain.sync.dto;

import java.util.UUID;

/**
 * Dispatcher가 후속 처리 Transaction에 전달하는 최소 Event 소유권 Snapshot이다.
 *
 * <p>Payload와 도메인 상태는 Dispatch 시 DB에서 다시 읽고, 이 DTO는 Event ID와 현재 Claim Token만
 * 전달해 오래된 Scheduler 실행이 새 소유권으로 작업하지 못하게 한다.
 */
public record ClaimedSyncEvent(UUID eventId, UUID claimToken) {
}
