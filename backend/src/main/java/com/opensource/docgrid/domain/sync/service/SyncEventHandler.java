package com.opensource.docgrid.domain.sync.service;

import java.util.Set;

import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;

/**
 * Sync Event Type별 멱등 부작용을 수행하는 Handler 경계다.
 *
 * <p>구현체는 현재 Dispatch Transaction에 참여하며 외부 Commit을 직접 만들지 않는다. 같은 Event가
 * 반복 호출돼도 최종 도메인 상태가 한 번 처리한 결과와 같아야 한다.
 */
public interface SyncEventHandler {

    Set<SyncEventType> supportedTypes();

    void handle(SyncOutboxEvent event);
}
