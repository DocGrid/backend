package com.opensource.docgrid.domain.search.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.search.benchmark.VectorSearchPerformanceBenchmark.BenchmarkConfiguration;
import com.opensource.docgrid.domain.search.benchmark.VectorSearchPerformanceBenchmark.RecallSummary;
import com.opensource.docgrid.domain.search.benchmark.VectorSearchPerformanceBenchmark.TimingSummary;
import com.opensource.docgrid.domain.search.benchmark.VectorSearchPerformanceBenchmark.VectorResult;

/**
 * 실제 DB를 사용하지 않고 Vector 검색 Benchmark의 입력 검증, Recall과 Percentile 계약을 검증한다.
 */
@DisplayName("Vector 검색 성능 Benchmark 계약 테스트")
class VectorSearchPerformanceBenchmarkTest {

    @Test
    @DisplayName("쉼표로 구분한 데이터 규모를 입력 순서대로 파싱한다")
    void parseRowCounts_preservesInputOrder() {
        assertThat(BenchmarkConfiguration.parseRowCounts("2000, 10000,50000"))
            .containsExactly(2_000, 10_000, 50_000);
    }

    @Test
    @DisplayName("데이터 규모가 오름차순이 아니거나 중복이면 거부한다")
    void configuration_rejectsUnorderedOrDuplicateRowCounts() {
        assertThatThrownBy(() -> configuration(List.of(10_000, 2_000), 10))
            .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> configuration(List.of(2_000, 2_000), 10))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("가장 작은 데이터 규모가 Top-K보다 작으면 거부한다")
    void configuration_rejectsRowCountSmallerThanTopK() {
        assertThatThrownBy(() -> configuration(List.of(5, 10), 10))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("Exact와 HNSW Top-K 교집합으로 평균·최소 Recall을 계산한다")
    void recallAtK_usesExactResultAsGroundTruth() {
        Map<Long, List<VectorResult>> exact = Map.of(
            1L,
            List.of(result(1), result(2), result(3), result(4)),
            2L,
            List.of(result(5), result(6), result(7), result(8))
        );
        Map<Long, List<VectorResult>> hnsw = Map.of(
            1L,
            List.of(result(1), result(2), result(9), result(10)),
            2L,
            List.of(result(5), result(6), result(7), result(11))
        );

        RecallSummary summary = VectorSearchPerformanceBenchmark.recallAtK(exact, hnsw, 4);

        assertThat(summary.average()).isEqualTo(0.625);
        assertThat(summary.minimum()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("표본 수와 nearest-rank p50·p95·p99·최댓값을 계산한다")
    void timingSummary_usesNearestRankPercentiles() {
        TimingSummary summary = TimingSummary.from(
            List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
        );

        assertThat(summary.sampleCount()).isEqualTo(10);
        assertThat(summary.p50Millis()).isEqualTo(5.0);
        assertThat(summary.p95Millis()).isEqualTo(10.0);
        assertThat(summary.p99Millis()).isEqualTo(10.0);
        assertThat(summary.maxMillis()).isEqualTo(10.0);
    }

    private BenchmarkConfiguration configuration(List<Integer> rowCounts, int topK) {
        return new BenchmarkConfiguration(rowCounts, 2, 1, 2, topK, Path.of("result.json"));
    }

    private VectorResult result(long id) {
        return new VectorResult(id, id / 100.0);
    }
}
