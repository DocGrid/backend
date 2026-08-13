package com.opensource.docgrid.e2e;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * PDF·DOCX E2E 부하 Benchmark의 형식 혼합과 형식별 처리량·지연 계산을 담당한다.
 *
 * <p>실제 Infrastructure I/O와 분리된 순수 계산만 제공해 Profile 결과 집계 계약을 일반 단위
 * 테스트에서 검증할 수 있게 한다.
 */
final class DocumentIndexingE2ELoadStatistics {

    private DocumentIndexingE2ELoadStatistics() {
    }

    static void validateBalancedMix(List<DocumentMeasurement> measurements) {
        if (measurements == null || measurements.isEmpty()) {
            throw new IllegalArgumentException("문서 측정값은 비어 있을 수 없습니다.");
        }

        long pdfCount = measurements.stream()
            .filter(measurement -> measurement.format() == DocumentFormat.PDF)
            .count();
        long docxCount = measurements.stream()
            .filter(measurement -> measurement.format() == DocumentFormat.DOCX)
            .count();
        if (pdfCount != docxCount) {
            throw new IllegalArgumentException("PDF와 DOCX 문서 수는 같아야 합니다.");
        }
    }

    static Map<DocumentFormat, FormatSummary> summarizeByFormat(
        List<DocumentMeasurement> measurements,
        double elapsedSeconds
    ) {
        validateBalancedMix(measurements);
        if (!Double.isFinite(elapsedSeconds) || elapsedSeconds <= 0.0) {
            throw new IllegalArgumentException("Profile 경과 시간은 0보다 큰 유한한 값이어야 합니다.");
        }

        Map<DocumentFormat, FormatSummary> summaries = new EnumMap<>(DocumentFormat.class);
        for (DocumentFormat format : DocumentFormat.values()) {
            List<DocumentMeasurement> formatMeasurements = measurements.stream()
                .filter(measurement -> measurement.format() == format)
                .toList();
            summaries.put(format, summarize(formatMeasurements, elapsedSeconds));
        }
        return Map.copyOf(summaries);
    }

    private static FormatSummary summarize(
        List<DocumentMeasurement> measurements,
        double elapsedSeconds
    ) {
        int documentCount = measurements.size();
        int chunkCount = measurements.stream().mapToInt(DocumentMeasurement::chunkCount).sum();
        int embeddingCount = measurements.stream().mapToInt(DocumentMeasurement::embeddingCount).sum();
        double documentsPerSecond = documentCount / elapsedSeconds;

        return new FormatSummary(
            documentCount,
            chunkCount,
            embeddingCount,
            documentsPerSecond,
            documentsPerSecond * 60.0,
            chunkCount / elapsedSeconds,
            embeddingCount / elapsedSeconds,
            latency(measurements.stream().map(DocumentMeasurement::uploadMillis).toList()),
            latency(measurements.stream().map(DocumentMeasurement::queueMillis).toList()),
            latency(measurements.stream().map(DocumentMeasurement::processingMillis).toList()),
            latency(measurements.stream().map(DocumentMeasurement::e2eMillis).toList())
        );
    }

    private static LatencySummary latency(List<Double> values) {
        return new LatencySummary(
            WorkerIndexingThroughputStatistics.percentile(values, 50.0),
            WorkerIndexingThroughputStatistics.percentile(values, 95.0),
            WorkerIndexingThroughputStatistics.percentile(values, 99.0),
            values.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
        );
    }

    /** 부하 Fixture와 결과 집계에서 공유하는 지원 문서 형식이다. */
    enum DocumentFormat {
        PDF,
        DOCX
    }

    /** 한 문서의 형식, 구간별 지연과 저장 결과를 형식별 통계 입력으로 전달한다. */
    record DocumentMeasurement(
        DocumentFormat format,
        double uploadMillis,
        double queueMillis,
        double processingMillis,
        double e2eMillis,
        int chunkCount,
        int embeddingCount
    ) {

        DocumentMeasurement {
            if (format == null) {
                throw new IllegalArgumentException("문서 형식은 필수입니다.");
            }
            if (!isNonNegativeFinite(uploadMillis)
                || !isNonNegativeFinite(queueMillis)
                || !isNonNegativeFinite(processingMillis)
                || !isNonNegativeFinite(e2eMillis)) {
                throw new IllegalArgumentException("지연 측정값은 0 이상의 유한한 값이어야 합니다.");
            }
            if (chunkCount < 0 || embeddingCount < 0) {
                throw new IllegalArgumentException("Chunk와 Embedding 수는 음수일 수 없습니다.");
            }
        }

        private static boolean isNonNegativeFinite(double value) {
            return Double.isFinite(value) && value >= 0.0;
        }
    }

    /** 한 구간 지연 표본의 p50·p95·p99와 최댓값을 밀리초 단위로 보존한다. */
    record LatencySummary(double p50, double p95, double p99, double max) {
    }

    /** 한 문서 형식의 처리량, 저장량과 구간별 지연 분포를 구조화한다. */
    record FormatSummary(
        int documentCount,
        int chunkCount,
        int embeddingCount,
        double documentsPerSecond,
        double documentsPerMinute,
        double chunksPerSecond,
        double embeddingsPerSecond,
        LatencySummary uploadLatencyMillis,
        LatencySummary queueLatencyMillis,
        LatencySummary processingLatencyMillis,
        LatencySummary e2eLatencyMillis
    ) {
    }
}
