package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Worker 처리량 Benchmark가 사용하는 선형 보간 Percentile과 입력 검증 계약을 확인한다.
 */
@DisplayName("Worker 인덱싱 처리량 통계 단위 테스트")
class WorkerIndexingThroughputStatisticsTest {

    @Test
    @DisplayName("정렬되지 않은 짝수 표본의 중앙값과 p95를 선형 보간한다")
    void percentile_interpolatesUnsortedEvenSamples() {
        List<Double> samples = List.of(40.0, 10.0, 30.0, 20.0);

        assertThat(WorkerIndexingThroughputStatistics.median(samples)).isEqualTo(25.0);
        assertThat(WorkerIndexingThroughputStatistics.percentile(samples, 95.0)).isEqualTo(38.5);
    }

    @Test
    @DisplayName("단일 표본은 모든 Percentile에서 같은 값을 반환한다")
    void percentile_returnsOnlySample() {
        assertThat(WorkerIndexingThroughputStatistics.percentile(List.of(17.0), 99.0))
            .isEqualTo(17.0);
    }

    @Test
    @DisplayName("빈 표본이나 유효 범위 밖 Percentile은 거부한다")
    void percentile_rejectsInvalidInput() {
        assertThatThrownBy(() -> WorkerIndexingThroughputStatistics.percentile(List.of(), 50.0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkerIndexingThroughputStatistics.percentile(List.of(1.0), 101.0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
