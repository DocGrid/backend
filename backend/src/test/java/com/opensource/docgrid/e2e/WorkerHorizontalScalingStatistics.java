package com.opensource.docgrid.e2e;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Worker 수평 확장 Benchmark의 Profile 입력과 Baseline 대비 비교 지표를 계산한다.
 *
 * <p>실제 인프라 실행과 분리된 순수 계산만 담당해 Profile 오입력, Speedup과 Slot 기준 효율 계산을
 * 일반 단위 테스트에서 빠르게 검증할 수 있게 한다.
 */
final class WorkerHorizontalScalingStatistics {

    private static final WorkerProfile BASELINE = new WorkerProfile(1, 1);

    private WorkerHorizontalScalingStatistics() {
    }

    static List<WorkerProfile> parseProfiles(String rawValue, List<WorkerProfile> defaults) {
        List<WorkerProfile> profiles = rawValue == null || rawValue.isBlank()
            ? List.copyOf(defaults)
            : List.of(rawValue.split(",")).stream()
                .map(String::trim)
                .map(WorkerHorizontalScalingStatistics::parseProfile)
                .toList();

        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("Worker 수평 확장 Profile은 하나 이상이어야 합니다.");
        }
        Set<WorkerProfile> uniqueProfiles = new LinkedHashSet<>(profiles);
        if (uniqueProfiles.size() != profiles.size()) {
            throw new IllegalArgumentException("Worker 수평 확장 Profile은 중복될 수 없습니다.");
        }
        if (!uniqueProfiles.contains(BASELINE)) {
            throw new IllegalArgumentException("Speedup 기준 Profile 1x1이 필요합니다.");
        }
        return List.copyOf(uniqueProfiles);
    }

    static double speedup(double baselineDocumentsPerSecond, double documentsPerSecond) {
        if (baselineDocumentsPerSecond <= 0.0) {
            throw new IllegalArgumentException("Baseline 처리량은 0보다 커야 합니다.");
        }
        if (documentsPerSecond < 0.0) {
            throw new IllegalArgumentException("Profile 처리량은 0보다 작을 수 없습니다.");
        }
        return documentsPerSecond / baselineDocumentsPerSecond;
    }

    static double scalingEfficiency(double speedup, int totalSlots) {
        if (speedup < 0.0) {
            throw new IllegalArgumentException("Speedup은 0보다 작을 수 없습니다.");
        }
        if (totalSlots < 1) {
            throw new IllegalArgumentException("전체 실행 Slot 수는 1 이상이어야 합니다.");
        }
        return speedup / totalSlots;
    }

    private static WorkerProfile parseProfile(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("x", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                "Worker 수평 확장 Profile은 workerCountxslotsPerWorker 형식이어야 합니다: " + value
            );
        }
        try {
            return new WorkerProfile(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Worker 수평 확장 Profile에는 양의 정수만 사용할 수 있습니다: " + value,
                exception
            );
        }
    }

    /** Worker 수와 Worker별 실행 Slot 수를 하나의 측정 Profile로 표현한다. */
    record WorkerProfile(int workerCount, int slotsPerWorker) {

        WorkerProfile {
            if (workerCount < 1 || slotsPerWorker < 1) {
                throw new IllegalArgumentException("Worker 수와 Worker별 실행 Slot 수는 1 이상이어야 합니다.");
            }
        }

        int totalSlots() {
            return Math.multiplyExact(workerCount, slotsPerWorker);
        }

        String name() {
            return "w" + workerCount + "-s" + slotsPerWorker;
        }
    }
}
