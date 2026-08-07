package com.opensource.docgrid.domain.mcp.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@Component
public class McpRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private record Window(AtomicInteger count, AtomicLong windowStartMillis) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void checkLimit(Long userId, String toolName, int limitPerMinute) {
        String key = userId + ":" + toolName;
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window(new AtomicInteger(0), new AtomicLong(now)));

        // 1분 지난 윈도우면 카운터를 리셋한다. 여러 스레드가 동시에 만료를 감지해도
        // compareAndSet으로 딱 한 스레드만 리셋에 성공하고 나머지는 건너뛴다.
        long windowStart = window.windowStartMillis().get();
        if (now - windowStart >= WINDOW_MILLIS && window.windowStartMillis().compareAndSet(windowStart, now)) {
            window.count().set(0);
        }

        // incrementAndGet은 원자적이라, 동시에 여러 요청이 들어와도 정확히 limit개까지만 통과한다.
        if (window.count().incrementAndGet() > limitPerMinute) {
            throw new DocGridException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }
}
