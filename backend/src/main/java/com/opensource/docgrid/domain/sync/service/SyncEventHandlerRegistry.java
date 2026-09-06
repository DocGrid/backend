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

    /**
     * Spring이 발견한 Handler를 지원 Event Type별로 등록한다.
     *
     * @throws IllegalStateException 같은 Event Type을 둘 이상의 Handler가 선언한 경우
     */
    public SyncEventHandlerRegistry(List<SyncEventHandler> candidates) {
        // 1. 각 Handler가 지원한다고 선언한 모든 Event Type을 Registry에 펼친다.
        for (SyncEventHandler candidate : candidates) {
            for (SyncEventType eventType : candidate.supportedTypes()) {
                // 2. 먼저 등록된 Handler를 덮어쓰지 않고 중복 구성을 애플리케이션 시작 시점에 드러낸다.
                SyncEventHandler previous = handlers.putIfAbsent(eventType, candidate);
                if (previous != null) {
                    throw new IllegalStateException("Sync Event Type별 Handler는 하나만 존재해야 합니다: " + eventType);
                }
            }
        }
    }

    /**
     * Event Type에 대응하는 유일 Handler를 찾아 Event 처리를 위임한다.
     */
    public void handle(SyncOutboxEvent event) {
        // 1. 저장된 Event Type으로 기동 시 확정한 Handler를 조회한다.
        SyncEventHandler handler = handlers.get(event.getEventType());

        // 2. 지원되지 않는 Type이 Queue에서 성공 처리되지 않도록 정합성 오류로 차단한다.
        if (handler == null) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }

        // 3. 실제 멱등 처리와 최신 원장 검증은 도메인별 Handler에 맡긴다.
        handler.handle(event);
    }
}
