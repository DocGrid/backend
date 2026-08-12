package com.opensource.docgrid.domain.embedding.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.benchmark.VectorStoragePerformanceBenchmark.BenchmarkConfiguration;
import com.opensource.docgrid.domain.embedding.benchmark.VectorStoragePerformanceBenchmark.TimingSummary;

/**
 * Vector 저장 Benchmark의 Profile 순환, Batch 호출 수, TPS와 설정 검증 계약을 보호한다.
 */
@DisplayName("Vector 저장 Benchmark 지원 계약")
class VectorStoragePerformanceBenchmarkTest {

    @Test
    @DisplayName("저장 규모별 시작 Batch Size를 결정적으로 회전한다")
    void rotate_changesStartingBatchSizeByScale() {
        List<Integer> batchSizes = List.of(1, 100, 500, 1_000);

        assertThat(VectorStoragePerformanceBenchmark.rotate(batchSizes, 0))
            .containsExactly(1, 100, 500, 1_000);
        assertThat(VectorStoragePerformanceBenchmark.rotate(batchSizes, 1))
            .containsExactly(100, 500, 1_000, 1);
        assertThat(VectorStoragePerformanceBenchmark.rotate(batchSizes, 2))
            .containsExactly(500, 1_000, 1, 100);
    }

    @Test
    @DisplayName("마지막 부분 Batch를 포함한 실행 횟수를 계산한다")
    void expectedBatchExecutions_includesPartialFinalBatch() {
        assertThat(VectorStoragePerformanceBenchmark.expectedBatchExecutions(1_000, 1)).isEqualTo(1_000);
        assertThat(VectorStoragePerformanceBenchmark.expectedBatchExecutions(1_000, 100)).isEqualTo(10);
        assertThat(VectorStoragePerformanceBenchmark.expectedBatchExecutions(1_001, 1_000)).isEqualTo(2);
        assertThat(VectorStoragePerformanceBenchmark.expectedBatchExecutions(Integer.MAX_VALUE, 2))
            .isEqualTo(1_073_741_824);

        assertThatThrownBy(() -> VectorStoragePerformanceBenchmark.expectedBatchExecutions(1_000, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Row 수와 Batch Size는 1 이상이어야 합니다.");
    }

    @Test
    @DisplayName("저장 시간에서 초당 Row 처리량을 계산한다")
    void rowsPerSecond_usesCommitInclusiveDuration() {
        assertThat(VectorStoragePerformanceBenchmark.rowsPerSecond(10_000, 2_000.0)).isEqualTo(5_000.0);

        assertThatThrownBy(() -> VectorStoragePerformanceBenchmark.rowsPerSecond(10_000, 0.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Row 수와 저장 시간은 0보다 큰 유한값이어야 합니다.");
    }

    @Test
    @DisplayName("반복 표본의 Nearest-rank 중앙값과 p95를 계산한다")
    void timingSummary_usesNearestRankPercentiles() {
        TimingSummary summary = TimingSummary.from(List.of(8.0, 1.0, 4.0, 2.0, 7.0, 3.0, 6.0, 5.0));

        assertThat(summary.sampleCount()).isEqualTo(8);
        assertThat(summary.minimum()).isEqualTo(1.0);
        assertThat(summary.p50()).isEqualTo(4.0);
        assertThat(summary.p95()).isEqualTo(8.0);
        assertThat(summary.maximum()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("설정은 빈 값, 중복과 0 이하 입력을 거부한다")
    void benchmarkConfiguration_rejectsInvalidProfiles() {
        assertThatThrownBy(() -> new BenchmarkConfiguration(
            List.of(1_000, 1_000),
            List.of(1, 100),
            1_000,
            2,
            1_024,
            Path.of("result.json")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("저장 규모 값은 중복될 수 없습니다.");

        assertThatThrownBy(() -> new BenchmarkConfiguration(
            List.of(1_000),
            List.of(0),
            1_000,
            2,
            1_024,
            Path.of("result.json")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Batch Size 값은 1 이상의 정수여야 합니다.");

        List<Integer> valuesWithNull = new ArrayList<>();
        valuesWithNull.add(1);
        valuesWithNull.add(null);
        assertThatThrownBy(() -> new BenchmarkConfiguration(
            List.of(1_000),
            valuesWithNull,
            1_000,
            2,
            1_024,
            Path.of("result.json")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Batch Size 값은 1 이상의 정수여야 합니다.");
    }

    @Test
    @DisplayName("쉼표 구분 Profile 값을 정수 목록으로 해석한다")
    void parsePositiveValues_parsesCommaSeparatedIntegers() {
        assertThat(BenchmarkConfiguration.parsePositiveValues("1000, 10000,100000", "저장 규모"))
            .containsExactly(1_000, 10_000, 100_000);

        assertThatThrownBy(() -> BenchmarkConfiguration.parsePositiveValues("1000,invalid", "저장 규모"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("저장 규모 값은 쉼표로 구분한 정수여야 합니다.");
    }
}
