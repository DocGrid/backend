package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.e2e.WorkerQueueBackpressureStatistics.LoadProfile;
import com.opensource.docgrid.e2e.WorkerQueueBackpressureStatistics.PressureState;
import com.opensource.docgrid.e2e.WorkerQueueBackpressureStatistics.QueueSample;
import com.opensource.docgrid.e2e.WorkerQueueBackpressureStatistics.QueueSummary;

/**
 * Worker Queue Backpressure Benchmark의 Profile, Queue 시간 지표와 압력 판정을 검증한다.
 */
@DisplayName("Worker Queue Backpressure 통계")
class WorkerQueueBackpressureStatisticsTest {

    @Test
    @DisplayName("문서 수와 업로드 Thread Profile을 오름차순으로 해석한다")
    void parsesLoadProfilesInAscendingOrder() {
        List<LoadProfile> profiles = WorkerQueueBackpressureStatistics.parseProfiles(
            "16x4, 32x8,64x16",
            List.of()
        );

        assertThat(profiles).containsExactly(
            new LoadProfile(16, 4),
            new LoadProfile(32, 8),
            new LoadProfile(64, 16)
        );
        assertThat(profiles).extracting(LoadProfile::name)
            .containsExactly("d16-u4", "d32-u8", "d64-u16");
    }

    @Test
    @DisplayName("중복되거나 감소하는 부하 Profile을 거부한다")
    void rejectsAmbiguousLoadProfiles() {
        assertThatThrownBy(() -> WorkerQueueBackpressureStatistics.parseProfiles(
            "16x4,16x4",
            List.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("중복");

        assertThatThrownBy(() -> WorkerQueueBackpressureStatistics.parseProfiles(
            "32x8,16x4",
            List.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("오름차순");

        assertThatThrownBy(() -> WorkerQueueBackpressureStatistics.parseProfiles(
            "16x8,32x4",
            List.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("감소");
    }

    @Test
    @DisplayName("Queue depth를 시간 적분하고 시간 가중 평균을 계산한다")
    void summarizesQueueDepthOverTime() {
        QueueSummary summary = WorkerQueueBackpressureStatistics.summarizeQueue(List.of(
            new QueueSample(2_000_000_000L, 0, 0, 0, 0),
            new QueueSample(3_000_000_000L, 4, 2, 0, 0),
            new QueueSample(5_000_000_000L, 0, 2, 4, 0),
            new QueueSample(6_000_000_000L, 0, 0, 6, 0)
        ));

        assertThat(summary.peakPendingJobs()).isEqualTo(4);
        assertThat(summary.peakQueueDepth()).isEqualTo(6);
        assertThat(summary.queueDepthAucDocumentSeconds()).isEqualTo(12.0);
        assertThat(summary.averageQueueDepth()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("Sample 비율 입력 경계를 검증한다")
    void calculatesValidatedSampleRatio() {
        assertThat(WorkerQueueBackpressureStatistics.sampleRatio(2, 8)).isEqualTo(0.25);
        assertThatThrownBy(() -> WorkerQueueBackpressureStatistics.sampleRatio(9, 8))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkerQueueBackpressureStatistics.sampleRatio(0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("실패, Pool 대기, Pool 포화 순서로 가장 심각한 상태를 판정한다")
    void classifiesMostSevereObservedPressureState() {
        assertThat(WorkerQueueBackpressureStatistics.classify(4, 3, 0, 0, 0, 0))
            .isEqualTo(PressureState.STABLE);
        assertThat(WorkerQueueBackpressureStatistics.classify(4, 4, 0, 0, 0, 0))
            .isEqualTo(PressureState.POOL_SATURATED);
        assertThat(WorkerQueueBackpressureStatistics.classify(4, 4, 2, 0, 0, 0))
            .isEqualTo(PressureState.POOL_BACKPRESSURED);
        assertThat(WorkerQueueBackpressureStatistics.classify(4, 4, 2, 1, 0, 0))
            .isEqualTo(PressureState.COLLAPSED);
        assertThat(WorkerQueueBackpressureStatistics.classify(4, 4, 0, 0, 1, 0))
            .isEqualTo(PressureState.COLLAPSED);
        assertThat(WorkerQueueBackpressureStatistics.classify(4, 4, 0, 0, 0, 1))
            .isEqualTo(PressureState.COLLAPSED);
    }
}
