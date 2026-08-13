package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.e2e.WorkerHorizontalScalingStatistics.WorkerProfile;

/** Worker 수평 확장 Profile 파싱과 비교 지표의 순수 계산 계약을 검증한다. */
@DisplayName("Worker 수평 확장 통계")
class WorkerHorizontalScalingStatisticsTest {

    @Test
    @DisplayName("Worker 수와 Slot 수 Profile을 입력 순서대로 파싱한다")
    void parsesWorkerAndSlotProfilesInOrder() {
        List<WorkerProfile> profiles = WorkerHorizontalScalingStatistics.parseProfiles(
            "1x1, 1x2,2x1,4x2",
            List.of()
        );

        assertThat(profiles).containsExactly(
            new WorkerProfile(1, 1),
            new WorkerProfile(1, 2),
            new WorkerProfile(2, 1),
            new WorkerProfile(4, 2)
        );
        assertThat(profiles.get(3).name()).isEqualTo("w4-s2");
        assertThat(profiles.get(3).totalSlots()).isEqualTo(8);
    }

    @Test
    @DisplayName("중복 Profile과 1x1 Baseline 누락을 거부한다")
    void rejectsDuplicateOrMissingBaselineProfiles() {
        assertThatThrownBy(() -> WorkerHorizontalScalingStatistics.parseProfiles(
            "1x1,1x1",
            List.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("중복");

        assertThatThrownBy(() -> WorkerHorizontalScalingStatistics.parseProfiles(
            "2x1,2x2",
            List.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1x1");
    }

    @Test
    @DisplayName("Baseline 처리량 대비 Speedup과 전체 Slot 기준 효율을 계산한다")
    void calculatesSpeedupAndScalingEfficiency() {
        double speedup = WorkerHorizontalScalingStatistics.speedup(2.0, 6.0);

        assertThat(speedup).isEqualTo(3.0);
        assertThat(WorkerHorizontalScalingStatistics.scalingEfficiency(speedup, 4))
            .isEqualTo(0.75);
    }
}
