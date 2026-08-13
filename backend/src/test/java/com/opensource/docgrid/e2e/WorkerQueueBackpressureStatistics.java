package com.opensource.docgrid.e2e;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Worker Queue Backpressure Benchmark의 입력 Profile과 시간 기반 Queue 지표를 계산한다.
 *
 * <p>실제 Infrastructure I/O와 분리된 순수 계산만 담당해 Profile 오입력, Queue AUC와
 * DB Connection Pool 압력 상태 판정 계약을 일반 단위 테스트에서 회귀 검증할 수 있게 한다.
 */
final class WorkerQueueBackpressureStatistics {

    private WorkerQueueBackpressureStatistics() {
    }

    static List<LoadProfile> parseProfiles(String rawValue, List<LoadProfile> defaults) {
        List<LoadProfile> profiles = rawValue == null || rawValue.isBlank()
            ? List.copyOf(defaults)
            : List.of(rawValue.split(",")).stream()
                .map(String::trim)
                .map(WorkerQueueBackpressureStatistics::parseProfile)
                .toList();

        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("Queue Backpressure Profile은 하나 이상이어야 합니다.");
        }
        Set<LoadProfile> uniqueProfiles = new LinkedHashSet<>(profiles);
        if (uniqueProfiles.size() != profiles.size()) {
            throw new IllegalArgumentException("Queue Backpressure Profile은 중복될 수 없습니다.");
        }
        for (int index = 1; index < profiles.size(); index++) {
            LoadProfile previous = profiles.get(index - 1);
            LoadProfile current = profiles.get(index);
            if (current.documentCount() <= previous.documentCount()) {
                throw new IllegalArgumentException("Profile 문서 수는 오름차순으로 증가해야 합니다.");
            }
            if (current.uploaderThreads() < previous.uploaderThreads()) {
                throw new IllegalArgumentException("Profile 업로드 Thread 수는 감소할 수 없습니다.");
            }
        }
        return List.copyOf(uniqueProfiles);
    }

    static QueueSummary summarizeQueue(List<QueueSample> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("Queue Sample은 하나 이상이어야 합니다.");
        }

        List<QueueSample> validated = new ArrayList<>(samples.size());
        long previousElapsedNanos = -1L;
        int peakPending = 0;
        int peakQueueDepth = 0;
        double queueDepthAucDocumentSeconds = 0.0;

        for (QueueSample sample : samples) {
            if (sample == null) {
                throw new IllegalArgumentException("Queue Sample은 null일 수 없습니다.");
            }
            if (sample.elapsedNanos() < previousElapsedNanos) {
                throw new IllegalArgumentException("Queue Sample 시각은 감소할 수 없습니다.");
            }
            if (!validated.isEmpty()) {
                QueueSample previous = validated.get(validated.size() - 1);
                double intervalSeconds = (sample.elapsedNanos() - previous.elapsedNanos())
                    / 1_000_000_000.0;
                queueDepthAucDocumentSeconds += intervalSeconds
                    * (previous.queueDepth() + sample.queueDepth()) / 2.0;
            }
            validated.add(sample);
            previousElapsedNanos = sample.elapsedNanos();
            peakPending = Math.max(peakPending, sample.pendingJobs());
            peakQueueDepth = Math.max(peakQueueDepth, sample.queueDepth());
        }

        double elapsedSeconds = (validated.get(validated.size() - 1).elapsedNanos()
            - validated.get(0).elapsedNanos()) / 1_000_000_000.0;
        double averageQueueDepth = elapsedSeconds == 0.0
            ? validated.get(0).queueDepth()
            : queueDepthAucDocumentSeconds / elapsedSeconds;
        return new QueueSummary(
            peakPending,
            peakQueueDepth,
            queueDepthAucDocumentSeconds,
            averageQueueDepth
        );
    }

    static double sampleRatio(int matchingSamples, int totalSamples) {
        if (matchingSamples < 0 || totalSamples < 1 || matchingSamples > totalSamples) {
            throw new IllegalArgumentException("Sample 수는 0 이상 전체 Sample 수 이하여야 합니다.");
        }
        return (double) matchingSamples / totalSamples;
    }

    static PressureState classify(
        int maximumPoolSize,
        int maxActiveConnections,
        int maxAwaitingConnections,
        int uploadFailures,
        int failedJobs,
        int incompleteJobs
    ) {
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("Hikari maximum-pool-size는 1 이상이어야 합니다.");
        }
        if (maxActiveConnections < 0 || maxActiveConnections > maximumPoolSize) {
            throw new IllegalArgumentException("active connection 수가 Pool 범위를 벗어났습니다.");
        }
        if (maxAwaitingConnections < 0 || uploadFailures < 0 || failedJobs < 0 || incompleteJobs < 0) {
            throw new IllegalArgumentException("대기와 실패 수는 0 이상이어야 합니다.");
        }

        if (uploadFailures > 0 || failedJobs > 0 || incompleteJobs > 0) {
            return PressureState.COLLAPSED;
        }
        if (maxAwaitingConnections > 0) {
            return PressureState.POOL_BACKPRESSURED;
        }
        if (maxActiveConnections == maximumPoolSize) {
            return PressureState.POOL_SATURATED;
        }
        return PressureState.STABLE;
    }

    private static LoadProfile parseProfile(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("x", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                "Queue Backpressure Profile은 documentCountxuploaderThreads 형식이어야 합니다: " + value
            );
        }
        try {
            return new LoadProfile(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Queue Backpressure Profile에는 양의 정수만 사용할 수 있습니다: " + value,
                exception
            );
        }
    }

    /** 한 부하 단계의 문서 수와 동시 업로드 Thread 수를 표현한다. */
    record LoadProfile(int documentCount, int uploaderThreads) {

        LoadProfile {
            if (documentCount < 1 || uploaderThreads < 1) {
                throw new IllegalArgumentException("문서 수와 업로드 Thread 수는 1 이상이어야 합니다.");
            }
        }

        String name() {
            return "d" + documentCount + "-u" + uploaderThreads;
        }
    }

    /** 측정 시작 뒤 한 시점의 Job 상태별 Queue 깊이다. */
    record QueueSample(
        long elapsedNanos,
        int pendingJobs,
        int processingJobs,
        int indexedJobs,
        int failedJobs
    ) {

        QueueSample {
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException("Sample 경과 시간은 0 이상이어야 합니다.");
            }
            if (pendingJobs < 0 || processingJobs < 0 || indexedJobs < 0 || failedJobs < 0) {
                throw new IllegalArgumentException("Job 상태별 수는 0 이상이어야 합니다.");
            }
        }

        int queueDepth() {
            return Math.addExact(pendingJobs, processingJobs);
        }
    }

    /** Queue Sample 시계열에서 계산한 최대 깊이와 시간 적분 요약이다. */
    record QueueSummary(
        int peakPendingJobs,
        int peakQueueDepth,
        double queueDepthAucDocumentSeconds,
        double averageQueueDepth
    ) {
    }

    /** 관측된 Hikari 대기와 완료 실패를 심각도 순서로 표현한다. */
    enum PressureState {
        STABLE,
        POOL_SATURATED,
        POOL_BACKPRESSURED,
        COLLAPSED
    }
}
