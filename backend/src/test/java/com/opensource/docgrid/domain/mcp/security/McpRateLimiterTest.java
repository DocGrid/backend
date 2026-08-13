package com.opensource.docgrid.domain.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@DisplayName("McpRateLimiter 단위 테스트")
class McpRateLimiterTest {

    private final McpRateLimiter rateLimiter = new McpRateLimiter();

    @Test
    @DisplayName("정상 케이스: 제한 이내 호출은 전부 통과한다")
    void checkLimit_passes_whenWithinLimit() {
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkLimit(1L, "search_documents", 20);
        }
    }

    @Test
    @DisplayName("예외 케이스: 제한을 초과하면 RATE_LIMIT_EXCEEDED 예외가 발생한다")
    void checkLimit_throws_whenLimitExceeded() {
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkLimit(1L, "search_documents", 20);
        }

        assertThatThrownBy(() -> rateLimiter.checkLimit(1L, "search_documents", 20))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("정상 케이스: 도구가 다르면 카운터가 별도로 관리된다")
    void checkLimit_tracksSeparately_perTool() {
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkLimit(1L, "search_documents", 20);
        }

        // search_documents는 한도를 채웠지만 다른 도구는 영향받지 않는다
        rateLimiter.checkLimit(1L, "get_document_detail", 30);
    }

    @Test
    @DisplayName("정상 케이스: 사용자가 다르면 카운터가 별도로 관리된다")
    void checkLimit_tracksSeparately_perUser() {
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkLimit(1L, "search_documents", 20);
        }

        rateLimiter.checkLimit(2L, "search_documents", 20);
    }

    @Test
    @DisplayName("동시성 케이스: 동시에 몰려도 정확히 limit개만 통과하고 나머지는 차단된다")
    void checkLimit_allowsExactlyLimit_whenCalledConcurrently() throws InterruptedException {
        int limit = 20;
        int totalCalls = 50;
        ExecutorService executor = Executors.newFixedThreadPool(totalCalls);
        CountDownLatch ready = new CountDownLatch(totalCalls);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalCalls);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < totalCalls; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    rateLimiter.checkLimit(1L, "search_documents", limit);
                    successCount.incrementAndGet();
                } catch (DocGridException e) {
                    rejectedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(limit);
        assertThat(rejectedCount.get()).isEqualTo(totalCalls - limit);
    }

    @Test
    @DisplayName("동시성 케이스: 윈도우 만료 경계에서 동시 호출해도 리셋과 증가가 꼬이지 않고 정확히 limit개만 통과한다")
    void checkLimit_handlesWindowExpiryRace_correctly() throws InterruptedException {
        // 윈도우 만료 경계를 짧은 시간 안에 재현하기 위해 테스트 전용 생성자로 window를 50ms로 줄인다
        McpRateLimiter shortWindowLimiter = new McpRateLimiter(50);
        int limit = 10;

        // 첫 윈도우를 한도까지 채운다
        for (int i = 0; i < limit; i++) {
            shortWindowLimiter.checkLimit(1L, "tool", limit);
        }

        // 윈도우가 만료되도록 대기
        Thread.sleep(60);

        // 새 윈도우 경계에서 여러 스레드가 동시에 몰린다 —
        // 리셋과 증가가 하나의 동기화 구역으로 묶여있지 않으면 일부 요청이 리셋 전 카운터로
        // 증가해 부당하게 거부되거나, 리셋에 의해 증가분이 사라지는 레이스가 발생할 수 있다.
        int totalCalls = 30;
        ExecutorService executor = Executors.newFixedThreadPool(totalCalls);
        CountDownLatch ready = new CountDownLatch(totalCalls);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalCalls);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < totalCalls; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    shortWindowLimiter.checkLimit(1L, "tool", limit);
                    successCount.incrementAndGet();
                } catch (DocGridException e) {
                    rejectedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(limit);
        assertThat(rejectedCount.get()).isEqualTo(totalCalls - limit);
    }
}
