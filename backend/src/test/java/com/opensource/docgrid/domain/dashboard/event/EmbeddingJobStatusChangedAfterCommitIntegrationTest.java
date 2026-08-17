package com.opensource.docgrid.domain.dashboard.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.embedding.event.EmbeddingJobStatusChangedEvent;

/**
 * {@code EmbeddingJobStatusChangedEvent}가 실제로 커밋된 뒤에만 대시보드 push로 이어지고,
 * 롤백되면 push가 발생하지 않는지 실제 STOMP Client로 끝까지 관통해서 검증한다.
 *
 * <p>이슈2에서 확인했듯 {@code SimpleBrokerMessageHandler}는 STOMP Receipt를 지원하지 않으므로
 * 구독 완료 대기는 {@link SimpUserRegistry} 폴링으로 한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Embedding Job 상태 전이 이벤트 AFTER_COMMIT 통합 테스트")
class EmbeddingJobStatusChangedAfterCommitIntegrationTest {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";
    private static final long TIMEOUT_SECONDS = 5;
    private static final long SUBSCRIPTION_POLL_INTERVAL_MILLIS = 20;
    // 롤백 시 "push가 안 온다"를 증명하려면 debounce 주기보다 확실히 더 기다려야 한다.
    private static final String DEBOUNCE_INTERVAL_MS = "200";
    private static final long NO_PUSH_WAIT_MILLIS = 800;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    private WebSocketStompClient stompClient;
    private TransactionTemplate requiresNewTransactionTemplate;

    @DynamicPropertySource
    static void configureDashboard(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> "docgrid-after-commit-integration-test-secret-key-2026");
        registry.add("dashboard.push.debounce-interval-ms", () -> DEBOUNCE_INTERVAL_MS);
    }

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("정상 케이스: 이벤트를 발행한 Transaction이 커밋되면 대시보드 push가 도착한다")
    void push_arrives_afterEventPublishingTransactionCommits() throws Exception {
        // Given
        BlockingQueue<DashboardSummaryResponse> received = new LinkedBlockingQueue<>();
        StompSession session = connectAndSubscribe(received);

        // When — 실제 커밋되는 Transaction 안에서 이벤트를 발행한다.
        requiresNewTransactionTemplate.executeWithoutResult(status ->
            applicationEventPublisher.publishEvent(new EmbeddingJobStatusChangedEvent(1L))
        );

        // Then
        DashboardSummaryResponse result = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(result).isNotNull();

        session.disconnect();
    }

    @Test
    @DisplayName("예외 케이스: 이벤트를 발행한 Transaction이 롤백되면 대시보드 push가 발생하지 않는다")
    void push_doesNotArrive_whenEventPublishingTransactionRollsBack() throws Exception {
        // Given
        BlockingQueue<DashboardSummaryResponse> received = new LinkedBlockingQueue<>();
        StompSession session = connectAndSubscribe(received);

        // When — 이벤트는 발행하지만 같은 Transaction을 명시적으로 롤백시킨다.
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            applicationEventPublisher.publishEvent(new EmbeddingJobStatusChangedEvent(2L));
            status.setRollbackOnly();
        });

        // Then — debounce 주기(200ms)보다 확실히 긴 시간을 기다려도 push가 없어야 한다.
        Thread.sleep(NO_PUSH_WAIT_MILLIS);
        assertThat(received).isEmpty();

        session.disconnect();
    }

    private StompSession connectAndSubscribe(
        BlockingQueue<DashboardSummaryResponse> received
    ) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + adminToken());

        StompSession session = stompClient
            .connectAsync(wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() { })
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        session.subscribe(DASHBOARD_TOPIC, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return DashboardSummaryResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((DashboardSummaryResponse) payload);
            }
        });

        awaitSubscriptionRegistered();
        return session;
    }

    private void awaitSubscriptionRegistered() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            boolean registered = !simpUserRegistry
                .findSubscriptions(subscription -> DASHBOARD_TOPIC.equals(subscription.getDestination()))
                .isEmpty();
            if (registered) {
                return;
            }
            Thread.sleep(SUBSCRIPTION_POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError("구독이 " + TIMEOUT_SECONDS + "초 안에 서버에 등록되지 않았습니다.");
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws/websocket";
    }

    private String adminToken() {
        return jwtProvider.generateToken(1L, "after-commit-admin@example.com");
    }
}
