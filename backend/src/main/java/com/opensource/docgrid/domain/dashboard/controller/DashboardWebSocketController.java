package com.opensource.docgrid.domain.dashboard.controller;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;

import lombok.RequiredArgsConstructor;

/**
 * RAGOps Dashboard 집계 지표를 {@code /topic/dashboard} 구독자에게 push하는 전송 계층.
 *
 * <p>이 컨트롤러는 직접 지표를 집계하지 않는다. 호출하는 쪽(재처리 트리거, 상태 전이 이벤트
 * 리스너 등)이 {@code DashboardQueryService}로 최신 Snapshot을 계산해 넘겨주면 그대로
 * 브로드캐스트만 한다.
 */
@Component
@RequiredArgsConstructor
public class DashboardWebSocketController {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";

    private final SimpMessagingTemplate messagingTemplate;

    public void sendDashboardUpdate(DashboardSummaryResponse summary) {
        messagingTemplate.convertAndSend(DASHBOARD_TOPIC, summary);
    }
}
