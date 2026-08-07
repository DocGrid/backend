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
}
