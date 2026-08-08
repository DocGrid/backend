package com.opensource.docgrid.domain.embedding.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.worker.enums.IndexingEventType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자가 확인할 수 있는 인덱싱 상태 전이 Event 응답이다.
 *
 * <p>고정된 공개 메시지와 상태 전이만 제공하며 내부 진단 Snapshot인 Metadata JSON은 제외한다.
 */
public record AdminIndexingEventResponse(
    @Schema(description = "Event 식별자", example = "31")
    Long eventId,

    @Schema(description = "Event 유형", example = "RETRY")
    IndexingEventType eventType,

    @Schema(description = "전이 전 상태")
    String fromStatus,

    @Schema(description = "전이 후 상태")
    String toStatus,

    @Schema(description = "운영자에게 공개 가능한 Event 메시지")
    String message,

    @Schema(description = "Event 발생 시각")
    LocalDateTime occurredAt
) {
}
