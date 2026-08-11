package com.opensource.docgrid.domain.dashboard.event;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.service.query.DashboardQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 짧은 주기로 {@link DashboardUpdateFlag}를 확인해서, 그 사이 상태 전이가 있었을 때만 대시보드
 * 집계를 다시 계산해 push하는 debounce 스케줄러.
 *
 * <p>이벤트가 몇 번 들어왔든 한 주기(기본 300ms)당 최대 1번만 {@code getSummary()}(집계 쿼리 약
 * 9개)와 push를 실행한다. Burst 상황(전체 재처리, 시스템 장애로 다건 실패)에서 이벤트 개수만큼
 * DB를 두드리는 걸 막는 게 이 클래스의 유일한 목적이다.
 *
 * <p>이 클래스는 새 로직을 거의 안 만들고, 이미 있는 세 부품을 "언제 조합해서 실행할지"만
 * 정한다: "바뀌었는지 확인"은 {@link DashboardUpdateFlag}, "최신 집계 계산"은
 * {@code DashboardQueryService}(이슈1), "WebSocket 전송"은
 * {@code DashboardWebSocketController}(이슈2)가 이미 만들어 둔 것을 그대로 가져다 쓴다.
 */
@Component
@RequiredArgsConstructor
public class DashboardPushScheduler {

    private final DashboardUpdateFlag dashboardUpdateFlag;
    private final DashboardQueryService dashboardQueryService;
    private final DashboardWebSocketController dashboardWebSocketController;

    /**
     * {@code fixedDelayString}이라 "이전 실행이 끝난 시점부터" 설정된 간격 뒤에 다음 실행이
     * 잡힌다({@code fixedRate}처럼 "시작 시점 기준 고정 주기"가 아니다) — 이 메서드가 드물게
     * 오래 걸려도 다음 실행과 겹치거나 밀리지 않는 안전한 쪽을 선택했다. 주기 값은
     * {@code application.yml}의 {@code dashboard.push.debounce-interval-ms}에서 읽는다.
     */
    @Scheduled(fixedDelayString = "${dashboard.push.debounce-interval-ms}")
    public void pushIfDirty() {
        // 1. 그 사이 상태 전이가 있었는지 확인하면서 동시에 플래그를 내린다(원자적).
        if (dashboardUpdateFlag.consumeIfDirty()) {
            // 2. 있었을 때만 최신 집계를 처음부터 다시 계산해서 push한다. 없었으면 이 블록 자체가
            //    실행되지 않으므로 DB 조회도 push도 전혀 일어나지 않는다.
            dashboardWebSocketController.sendDashboardUpdate(dashboardQueryService.getSummary());
        }
    }
}
