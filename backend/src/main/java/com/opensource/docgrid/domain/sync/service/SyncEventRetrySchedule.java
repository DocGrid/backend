package com.opensource.docgrid.domain.sync.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.sync.config.SyncDispatcherProperties;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;

import lombok.RequiredArgsConstructor;

/**
 * Sync Event 실패 횟수에 따른 지수 Backoff를 설정 상한 안에서 계산한다.
 */
@Component
@RequiredArgsConstructor
public class SyncEventRetrySchedule {

    private final SyncDispatcherProperties syncDispatcherProperties;

    public LocalDateTime nextAvailableAt(SyncOutboxEvent event, LocalDateTime failedAt) {
        Duration delay = syncDispatcherProperties.getRetryInitialDelay();
        for (int index = 0; index < event.getRetryCount(); index++) {
            if (delay.compareTo(syncDispatcherProperties.getRetryMaxDelay()) >= 0) {
                delay = syncDispatcherProperties.getRetryMaxDelay();
                break;
            }
            delay = delay.multipliedBy(2);
        }
        if (delay.compareTo(syncDispatcherProperties.getRetryMaxDelay()) > 0) {
            delay = syncDispatcherProperties.getRetryMaxDelay();
        }
        return failedAt.plus(delay);
    }
}
