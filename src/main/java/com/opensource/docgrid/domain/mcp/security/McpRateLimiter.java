package com.opensource.docgrid.domain.mcp.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * MCP 도구 호출 빈도를 사용자·도구 단위로 제한한다. 서버 단일 인스턴스를 전제로 한
 * 인메모리 고정 윈도우(fixed window) 카운터이며, Redis 등 외부 저장소를 쓰지 않는다.
 */
@Component
public class McpRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private static final class Window {
        private long windowStartMillis;
        private int count;

        private Window(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final long windowMillis;

    public McpRateLimiter() {
        this(WINDOW_MILLIS);
    }

    // 테스트에서 윈도우 만료 경계를 짧은 시간 안에 재현할 수 있도록 window 길이를 주입받는다.
    McpRateLimiter(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public void checkLimit(Long userId, String toolName, int limitPerMinute) {
        String key = userId + ":" + toolName;
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window(now));

        // 리셋 여부 판단과 카운터 증가를 같은 동기화 구역에 묶어야 한다 — 분리하면 "리셋 직전에
        // 만료 전 카운터로 증가해버리는" 레이스가 생겨 새 윈도우의 첫 요청이 부당하게 막힐 수 있다.
        synchronized (window) {
            // 1. 윈도우가 만료됐으면 리셋
            if (now - window.windowStartMillis >= windowMillis) {
                window.windowStartMillis = now;
                window.count = 0;
            }
            // 2. 카운터 증가
            window.count++;
            // 3. 제한 초과 여부 판단
            if (window.count > limitPerMinute) {
                throw new DocGridException(ErrorCode.RATE_LIMIT_EXCEEDED);
            }
        }
    }
}
