package com.opensource.docgrid.domain.mcp.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * MCP 도구 호출 빈도를 사용자·도구 단위로 제한한다. 서버 단일 인스턴스를 전제로 한
 * 인메모리 고정 윈도우(fixed window) 카운터이며, Redis 등 외부 저장소를 쓰지 않는다.
 *
 * "고정 윈도우"란: 60초짜리 시간 구간을 하나 정해두고, 그 구간 안에서 호출 횟수를 센다.
 * 60초가 지나면 그 구간은 "만료"되고, 카운트는 0부터 다시 시작한다.
 * (이 제한은 "평생 총 N번"이 아니라 "매 1분마다 N번씩 다시 허용"하는 속도 제한이다.)
 */
@Component
public class McpRateLimiter {

    // 하나의 카운트 구간(윈도우) 길이 = 60초 = 60,000ms
    private static final long WINDOW_MILLIS = 60_000;

    // "사용자+도구 조합 하나"당 관리해야 하는 카운트 구간 정보를 담는 그릇
    private static final class Window {
        private long windowStartMillis; // 이 구간이 언제 시작됐는지 (이 시각으로부터 60초가 지나면 만료)
        private int count; // 이 구간이 시작된 뒤로 지금까지 호출된 횟수

        private Window(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
        }
    }

    // key = "userId:toolName" (예: "5:search_documents") → 그 조합 전용 Window
    // 사용자별·도구별로 완전히 독립된 카운터를 갖게 됨
    // ConcurrentHashMap: 여러 요청이 동시에 이 맵을 읽고 쓸 수 있으므로 스레드 안전한 구현체를 사용
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final long windowMillis;

    // 운영 환경에서 Spring이 빈을 만들 때 호출되는 생성자 — 윈도우 길이는 항상 60초로 고정
    public McpRateLimiter() {
        this(WINDOW_MILLIS);
    }

    // 테스트에서 윈도우 만료 경계를 짧은 시간 안에 재현할 수 있도록 window 길이를 주입받는다.
    // (실제로 60초를 기다릴 수 없으니, 테스트에서만 예: 100ms처럼 짧은 값을 넣어 빠르게 검증)
    McpRateLimiter(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /**
     * 사용자·도구 단위 호출 제한을 검사한다. 제한을 초과하면 DocGridException을 던진다.
     *
     * @param userId         사용자 ID
     * @param toolName       도구 이름
     * @param limitPerMinute 분당 호출 제한 횟수
     */
    public void checkLimit(Long userId, String toolName, int limitPerMinute) {
        // "5:search_documents"처럼 사용자+도구를 하나로 묶은 식별자
        String key = userId + ":" + toolName;
        long now = System.currentTimeMillis();
        // 이 조합을 처음 보는 거면 지금 시각으로 새 Window를 만들고, 이미 있으면 기존 것을 가져온다
        Window window = windows.computeIfAbsent(key, k -> new Window(now));

        // 리셋 여부 판단과 카운터 증가를 같은 동기화 구역에 묶어야 한다 — 분리하면 "리셋 직전에
        // 만료 전 카운터로 증가해버리는" 레이스가 생겨 새 윈도우의 첫 요청이 부당하게 막힐 수 있다.
        //
        // (과거에는 windowStartMillis/count를 AtomicLong/AtomicInteger로 따로 관리해서
        //  "만료 판단+시작시각 갱신"과 "카운트 리셋"이 원자적으로 묶여있지 않았다. 그 틈에
        //  다른 스레드가 끼어들면 "시작시각은 이미 새 걸로 바뀌었는데 카운트는 옛날 값 그대로"인
        //  상태를 보게 되어, 새 윈도우의 첫 요청이 부당하게 차단되거나 카운트가 유실되는
        //  레이스 컨디션이 있었다. 지금처럼 synchronized(window) 블록 하나로 전체를 묶으면
        //  이 틈 자체가 사라진다.)
        synchronized (window) {
            // 1. 윈도우가 만료됐으면(=시작된 지 60초 지났으면) 리셋
            //    → 새 구간 시작: 시작 시각을 지금으로, 카운트를 0으로
            if (now - window.windowStartMillis >= windowMillis) {
                window.windowStartMillis = now;
                window.count = 0;
            }
            // 2. 이번 호출을 카운트에 반영 (리셋됐으면 0→1, 아니면 기존 값에서 +1)
            window.count++;
            // 3. 이번 구간 안에서 허용치를 넘었는지 판단 — 넘었으면 호출 자체를 막는다
            if (window.count > limitPerMinute) {
                throw new DocGridException(ErrorCode.RATE_LIMIT_EXCEEDED);
            }
        }
    }
}