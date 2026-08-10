package com.opensource.docgrid.domain.dashboard.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.DocumentsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.JobsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.SearchSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.WorkersSummaryResponse;

/**
 * 실제 STOMP Client로 {@code /ws} 연결부터 {@code /topic/dashboard} 구독, 수동 push 수신까지
 * 관통하는 통합 테스트.
 *
 * <p>CONNECT 시점 JWT 검증({@code StompAuthChannelInterceptor})과 SUBSCRIBE 시점 ADMIN 권한
 * 검증({@code DashboardSubscriptionAuthorizationInterceptor})이 실제 Channel Interceptor
 * 체인에서 함께 동작하는지 확인한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Dashboard WebSocket 실시간 push 통합 테스트")
class DashboardWebSocketIntegrationTest {

    private static final long TIMEOUT_SECONDS = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private DashboardWebSocketController dashboardWebSocketController;

    private WebSocketStompClient stompClient;

    @DynamicPropertySource
    static void configureJwt(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> "docgrid-dashboard-websocket-integration-test-secret-key-2026");
    }

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @Test
    @DisplayName("정상 케이스: ADMIN 토큰으로 구독하면 sendDashboardUpdate() push를 즉시 수신한다")
    void receivesPush_whenAdminSubscribed() throws Exception {
        // Given
        BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
        StompSession session = connect(adminToken(), failures);
        BlockingQueue<DashboardSummaryResponse> received = new LinkedBlockingQueue<>();
        subscribeDashboard(session, received);

        // When
        DashboardSummaryResponse summary = sampleSummary();
        dashboardWebSocketController.sendDashboardUpdate(summary);

        // Then
        DashboardSummaryResponse result = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.documents().total()).isEqualTo(summary.documents().total());
        assertThat(result.jobs().failed()).isEqualTo(summary.jobs().failed());
        assertThat(failures).isEmpty();

        session.disconnect();
    }

    @Test
    @DisplayName("예외 케이스: 토큰 없이 CONNECT하면 연결이 거부된다")
    void rejectsConnect_whenNoToken() throws Exception {
        // Given
        BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
        StompHeaders connectHeaders = new StompHeaders();

        // When
        StompSession session = null;
        Throwable connectFailure = null;
        try {
            session = stompClient
                .connectAsync(wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, failureCapturingHandler(failures))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            connectFailure = e.getCause();
        }

        // Then — Transport 레벨에서 바로 실패하거나, 연결은 되고 STOMP 레벨 ERROR로 이어진다.
        if (session == null) {
            assertThat(connectFailure).isNotNull();
        } else {
            assertThat(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        }
    }

    @Test
    @DisplayName("예외 케이스: ADMIN이 아닌 사용자는 /topic/dashboard SUBSCRIBE가 거부된다")
    void rejectsSubscribe_whenNotAdmin() throws Exception {
        // Given
        BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
        StompSession session = connect(userToken(), failures);

        // When
        session.subscribe("/topic/dashboard", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return DashboardSummaryResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // 정상 케이스가 아니므로 페이로드를 받으면 안 된다.
            }
        });

        // Then — SUBSCRIBE 거부 시 서버가 세션을 이미 닫으므로 별도 disconnect()는 호출하지 않는다.
        assertThat(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
    }

    private StompSession connect(String token, BlockingQueue<Throwable> failures) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        return stompClient
            .connectAsync(wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, failureCapturingHandler(failures))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private StompSessionHandlerAdapter failureCapturingHandler(BlockingQueue<Throwable> failures) {
        return new StompSessionHandlerAdapter() {
            @Override
            public void handleException(
                StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception
            ) {
                failures.add(exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                failures.add(exception);
            }
        };
    }

    private void subscribeDashboard(StompSession session, BlockingQueue<DashboardSummaryResponse> received) {
        session.subscribe("/topic/dashboard", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return DashboardSummaryResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((DashboardSummaryResponse) payload);
            }
        });
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws/websocket";
    }

    private String adminToken() {
        return jwtProvider.generateToken(1L, "dashboard-admin@example.com", List.of("ADMIN"));
    }

    private String userToken() {
        return jwtProvider.generateToken(2L, "dashboard-user@example.com", List.of("USER"));
    }

    private DashboardSummaryResponse sampleSummary() {
        return new DashboardSummaryResponse(
            new DocumentsSummaryResponse(25368L, 21742L, 132L),
            new JobsSummaryResponse(132L, 8L, 27L, 3200L),
            new WorkersSummaryResponse(5L, 6L),
            new SearchSummaryResponse(342L)
        );
    }
}
