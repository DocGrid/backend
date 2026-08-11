package com.opensource.docgrid.domain.dashboard.event;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.service.query.DashboardQueryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardPushScheduler 단위 테스트")
class DashboardPushSchedulerTest {

    @InjectMocks private DashboardPushScheduler scheduler;

    @Mock private DashboardUpdateFlag dashboardUpdateFlag;
    @Mock private DashboardQueryService dashboardQueryService;
    @Mock private DashboardWebSocketController dashboardWebSocketController;

    @Test
    @DisplayName("정상 케이스: 플래그가 dirty였으면 집계를 계산해서 push한다")
    void pushIfDirty_computesAndPushes_whenFlagWasDirty() {
        // Given
        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(dashboardUpdateFlag.consumeIfDirty()).willReturn(true);
        given(dashboardQueryService.getSummary()).willReturn(summary);

        // When
        scheduler.pushIfDirty();

        // Then
        then(dashboardWebSocketController).should().sendDashboardUpdate(summary);
    }

    @Test
    @DisplayName("예외 케이스: 플래그가 dirty가 아니었으면 집계도 push도 하지 않는다")
    void pushIfDirty_doesNothing_whenFlagWasNotDirty() {
        // Given
        given(dashboardUpdateFlag.consumeIfDirty()).willReturn(false);

        // When
        scheduler.pushIfDirty();

        // Then
        then(dashboardQueryService).shouldHaveNoInteractions();
        then(dashboardWebSocketController).shouldHaveNoInteractions();
    }
}
