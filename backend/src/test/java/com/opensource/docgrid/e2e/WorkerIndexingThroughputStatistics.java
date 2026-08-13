package com.opensource.docgrid.e2e;

import java.util.ArrayList;
import java.util.List;

/**
 * Worker 처리량 Benchmark의 Percentile과 중앙값을 결정적으로 계산하는 Test 전용 통계 경계다.
 *
 * <p>측정 I/O와 분리된 순수 계산만 제공해 실제 Infrastructure 없이도 통계 계약을 회귀 검증한다.
 */
final class WorkerIndexingThroughputStatistics {

    private WorkerIndexingThroughputStatistics() {
    }

    static double median(List<Double> values) {
        return percentile(values, 50.0);
    }

    static double percentile(List<Double> values, double percentile) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Percentile 입력은 비어 있을 수 없습니다.");
        }
        if (!Double.isFinite(percentile) || percentile < 0.0 || percentile > 100.0) {
            throw new IllegalArgumentException("Percentile은 0 이상 100 이하여야 합니다.");
        }

        List<Double> sorted = new ArrayList<>(values.size());
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Percentile 입력은 유한한 수여야 합니다.");
            }
            sorted.add(value);
        }
        sorted.sort(Double::compareTo);

        double rank = percentile / 100.0 * (sorted.size() - 1);
        int lowerIndex = (int) Math.floor(rank);
        int upperIndex = (int) Math.ceil(rank);
        if (lowerIndex == upperIndex) {
            return sorted.get(lowerIndex);
        }

        double weight = rank - lowerIndex;
        return sorted.get(lowerIndex) * (1.0 - weight) + sorted.get(upperIndex) * weight;
    }
}
