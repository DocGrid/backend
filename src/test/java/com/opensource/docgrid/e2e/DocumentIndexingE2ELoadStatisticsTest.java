package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.e2e.DocumentIndexingE2ELoadStatistics.DocumentFormat;
import com.opensource.docgrid.e2e.DocumentIndexingE2ELoadStatistics.DocumentMeasurement;
import com.opensource.docgrid.e2e.DocumentIndexingE2ELoadStatistics.FormatSummary;

/**
 * PDF·DOCX E2E 부하 Benchmark가 동일한 형식 혼합과 형식별 통계 계약을 유지하는지 검증한다.
 */
@DisplayName("PDF DOCX 전체 인덱싱 부하 통계 단위 테스트")
class DocumentIndexingE2ELoadStatisticsTest {

    @Test
    @DisplayName("균형 잡힌 PDF DOCX 표본을 형식별 처리량과 지연으로 집계한다")
    void summarizeByFormat_aggregatesBalancedDocumentMix() {
        List<DocumentMeasurement> measurements = List.of(
            measurement(DocumentFormat.PDF, 100.0, 1, 1),
            measurement(DocumentFormat.DOCX, 200.0, 2, 2),
            measurement(DocumentFormat.PDF, 300.0, 3, 3),
            measurement(DocumentFormat.DOCX, 400.0, 4, 4)
        );

        Map<DocumentFormat, FormatSummary> summaries =
            DocumentIndexingE2ELoadStatistics.summarizeByFormat(measurements, 2.0);

        assertThat(summaries).containsOnlyKeys(DocumentFormat.PDF, DocumentFormat.DOCX);
        assertThat(summaries.get(DocumentFormat.PDF).documentCount()).isEqualTo(2);
        assertThat(summaries.get(DocumentFormat.PDF).chunkCount()).isEqualTo(4);
        assertThat(summaries.get(DocumentFormat.PDF).documentsPerSecond()).isEqualTo(1.0);
        assertThat(summaries.get(DocumentFormat.PDF).uploadLatencyMillis().p50()).isEqualTo(200.0);
        assertThat(summaries.get(DocumentFormat.DOCX).embeddingCount()).isEqualTo(6);
        assertThat(summaries.get(DocumentFormat.DOCX).documentsPerMinute()).isEqualTo(60.0);
        assertThat(summaries.get(DocumentFormat.DOCX).uploadLatencyMillis().max()).isEqualTo(400.0);
    }

    @Test
    @DisplayName("PDF DOCX 개수가 다르거나 Profile 시간이 유효하지 않으면 거부한다")
    void summarizeByFormat_rejectsInvalidProfile() {
        List<DocumentMeasurement> unbalanced = List.of(
            measurement(DocumentFormat.PDF, 100.0, 1, 1),
            measurement(DocumentFormat.PDF, 200.0, 1, 1),
            measurement(DocumentFormat.DOCX, 300.0, 1, 1)
        );

        assertThatThrownBy(() ->
            DocumentIndexingE2ELoadStatistics.summarizeByFormat(unbalanced, 1.0)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PDF와 DOCX");
        assertThatThrownBy(() ->
            DocumentIndexingE2ELoadStatistics.summarizeByFormat(
                List.of(
                    measurement(DocumentFormat.PDF, 100.0, 1, 1),
                    measurement(DocumentFormat.DOCX, 100.0, 1, 1)
                ),
                0.0
            )
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("경과 시간");
    }

    private DocumentMeasurement measurement(
        DocumentFormat format,
        double uploadMillis,
        int chunkCount,
        int embeddingCount
    ) {
        return new DocumentMeasurement(
            format,
            uploadMillis,
            uploadMillis + 10.0,
            uploadMillis + 20.0,
            uploadMillis + 30.0,
            chunkCount,
            embeddingCount
        );
    }
}
