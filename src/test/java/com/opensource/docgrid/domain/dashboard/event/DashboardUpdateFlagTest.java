package com.opensource.docgrid.domain.dashboard.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DashboardUpdateFlag 단위 테스트")
class DashboardUpdateFlagTest {

    @Test
    @DisplayName("정상 케이스: markDirty() 후 consumeIfDirty()는 true를 반환하고 플래그를 내린다")
    void consumeIfDirty_returnsTrueOnceThenFalse_afterMarkDirty() {
        // Given
        DashboardUpdateFlag flag = new DashboardUpdateFlag();
        flag.markDirty();

        // When & Then
        assertThat(flag.consumeIfDirty()).isTrue();
        assertThat(flag.consumeIfDirty()).isFalse();
    }

    @Test
    @DisplayName("정상 케이스: markDirty()를 여러 번 호출해도 consumeIfDirty()는 한 번만 true다 (coalesce)")
    void consumeIfDirty_coalescesMultipleMarkDirtyCalls() {
        // Given
        DashboardUpdateFlag flag = new DashboardUpdateFlag();
        flag.markDirty();
        flag.markDirty();
        flag.markDirty();

        // When & Then
        assertThat(flag.consumeIfDirty()).isTrue();
        assertThat(flag.consumeIfDirty()).isFalse();
    }

    @Test
    @DisplayName("예외 케이스: markDirty()를 호출한 적 없으면 consumeIfDirty()는 false다")
    void consumeIfDirty_returnsFalse_whenNeverMarkedDirty() {
        // Given
        DashboardUpdateFlag flag = new DashboardUpdateFlag();

        // When & Then
        assertThat(flag.consumeIfDirty()).isFalse();
    }
}
