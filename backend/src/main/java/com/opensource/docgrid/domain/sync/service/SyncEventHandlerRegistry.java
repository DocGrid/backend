package com.opensource.docgrid.domain.sync.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Event Type을 유일한 SyncEventHandler에 연결하고 Dispatch 진입점을 제공한다.
 *
 * <p>같은 Type을 둘 이상의 Handler가 선언하면 시작 단계에서 실패해 중복 부작용 가능성을 숨기지 않는다.
 */
@Component
public class SyncEventHandlerRegistry {

    private final Map<SyncEventType, SyncEventHandler> handlers = new EnumMap<>(SyncEventType.class);

    public SyncEventHandlerRegistry(List<SyncEventHandler> candidates) {
        for (SyncEventHandler candidate : candidates) {
            for (SyncEventType eventType : candidate.supportedTypes()) {
                SyncEventHandler previous = handlers.putIfAbsent(eventType, candidate);
                if (previous != null) {
                    throw new IllegalStateException("Sync Event Type별 Handler는 하나만 존재해야 합니다: " + eventType);
                }
            }
        }
    }

    public void handle(SyncOutboxEvent event) {
        SyncEventHandler handler = handlers.get(event.getEventType());
        if (handler == null) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
        handler.handle(event);
    }
}
