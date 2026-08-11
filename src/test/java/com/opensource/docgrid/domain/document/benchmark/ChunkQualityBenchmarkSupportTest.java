package com.opensource.docgrid.domain.document.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.ChunkCandidate;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.ChunkProfile;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.ChunkedCorpus;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.QualityMetrics;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.QueryCase;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.TimingSummary;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityPerformanceBenchmark.BenchmarkConfiguration;

/**
 * 실제 모델 없이 Chunk 품질 Benchmark의 Corpus, Ground Truth와 Exact 품질 계산을 검증한다.
 */
@DisplayName("Chunk Size·Overlap 품질 Benchmark 계약 테스트")
class ChunkQualityBenchmarkSupportTest {

    @Test
    @DisplayName("8개 Profile과 12개 경계 Query를 결정적인 순서로 생성한다")
    void createCorpus_preservesProfilesAndBoundaryCases() {
        List<ChunkProfile> profiles = ChunkQualityBenchmarkSupport.profiles();
        List<QueryCase> corpus = ChunkQualityBenchmarkSupport.createCorpus();

        assertThat(profiles).extracting(ChunkProfile::profileId)
            .containsExactly(
                "c400-o0", "c400-o80", "c800-o0", "c800-o160",
                "c1000-o0", "c1000-o200", "c1600-o0", "c1600-o320"
            );
        assertThat(corpus).hasSize(12);
        assertThat(corpus).extracting(QueryCase::targetBoundary)
            .containsExactly(400, 400, 400, 800, 800, 800, 1_000, 1_000, 1_000, 1_600, 1_600, 1_600);
        assertThat(corpus).allSatisfy(queryCase -> {
            assertThat(queryCase.documentText().codePointCount(0, queryCase.documentText().length()))
                .isEqualTo(ChunkQualityBenchmarkSupport.DOCUMENT_LENGTH);
            assertThat(queryCase.documentText().codePoints()
                .skip(queryCase.evidenceStart())
                .limit(queryCase.evidence().codePointCount(0, queryCase.evidence().length()))
                .toArray())
                .containsExactly(queryCase.evidence().codePoints().toArray());
        });
    }

    @Test
    @DisplayName("20% Overlap은 대응 경계의 완전한 근거 Chunk를 복구한다")
    void chunk_overlapRestoresEvidenceAcrossTargetBoundary() {
        List<QueryCase> corpus = ChunkQualityBenchmarkSupport.createCorpus();

        assertBoundaryCoverage(corpus, new ChunkProfile("without-overlap", 1_000, 0), 1_000, false);
        assertBoundaryCoverage(corpus, new ChunkProfile("with-overlap", 1_000, 200), 1_000, true);
    }

    @Test
    @DisplayName("완전한 근거 범위만 Relevant로 사용해 Hit@K와 MRR을 계산한다")
    void evaluate_usesFullEvidenceRangeAsGroundTruth() {
        QueryCase first = queryCase("q1", "doc1", 10, 20);
        QueryCase second = queryCase("q2", "doc2", 10, 20);
        List<ChunkCandidate> chunks = List.of(
            candidate("doc1:partial", "doc1", 0, 10, 15),
            candidate("doc1:answer", "doc1", 1, 5, 25),
            candidate("doc2:answer", "doc2", 0, 5, 25)
        );

        float[] axisX = vector(1.0F, 0.0F);
        float[] axisY = vector(0.0F, 1.0F);
        float[] diagonal = vector(0.8F, 0.6F);
        Map<String, float[]> queryVectors = Map.of("q1", axisX, "q2", axisY);
        Map<String, float[]> chunkVectors = Map.of(
            "doc1:partial", axisX,
            "doc1:answer", diagonal,
            "doc2:answer", axisY
        );

        QualityMetrics metrics = ChunkQualityBenchmarkSupport.evaluate(
            List.of(first, second),
            chunks,
            queryVectors,
            chunkVectors
        );

        assertThat(metrics.answerCoverageRatio()).isEqualTo(1.0);
        assertThat(metrics.hitAt1()).isEqualTo(0.5);
        assertThat(metrics.hitAt3()).isEqualTo(1.0);
        assertThat(metrics.mrrAt10()).isEqualTo(0.75);
        assertThat(metrics.queries()).extracting(result -> result.firstRelevantRank())
            .containsExactly(2, 1);
    }

    @Test
    @DisplayName("Vector 차원·유한값·Norm 불변식을 위반하면 거부한다")
    void validateVector_rejectsInvalidDenseVectors() {
        assertThatThrownBy(() -> ChunkQualityBenchmarkSupport.validateVector(new float[3]))
            .isInstanceOf(IllegalArgumentException.class);

        float[] notFinite = vector(1.0F, 0.0F);
        notFinite[7] = Float.NaN;
        assertThatThrownBy(() -> ChunkQualityBenchmarkSupport.validateVector(notFinite))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ChunkQualityBenchmarkSupport.validateVector(new float[1_024]))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잘못된 Chunk Size·Overlap 조합을 생성 단계에서 거부한다")
    void chunkProfile_rejectsInvalidOverlap() {
        assertThatThrownBy(() -> new ChunkProfile("invalid", 400, 400))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkProfile("invalid", 400, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("반복 지연의 Nearest-rank Median과 p95를 계산한다")
    void summarizeTimings_usesNearestRankPercentiles() {
        TimingSummary summary = ChunkQualityBenchmarkSupport.summarizeTimings(
            List.of(8.0, 1.0, 4.0, 2.0, 7.0, 3.0, 6.0, 5.0)
        );

        assertThat(summary.sampleCount()).isEqualTo(8);
        assertThat(summary.medianMillis()).isEqualTo(4.0);
        assertThat(summary.p95Millis()).isEqualTo(8.0);
        assertThat(summary.maxMillis()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("Timing null 표본은 정렬 전에 명시적인 입력 오류로 거부한다")
    void summarizeTimings_rejectsNullBeforeSorting() {
        List<Double> samples = new ArrayList<>();
        samples.add(1.0);
        samples.add(null);

        assertThatThrownBy(() -> ChunkQualityBenchmarkSupport.summarizeTimings(samples))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Timing 표본은 0 이상의 유한값이어야 합니다.");
    }

    @Test
    @DisplayName("Vector Map은 중복 ID로 앞선 Vector가 덮어써지는 것을 거부한다")
    void vectorMap_rejectsDuplicateIds() {
        assertThatThrownBy(() -> ChunkQualityBenchmarkSupport.vectorMap(
            List.of("duplicate", "duplicate"),
            List.of(vector(1.0F, 0.0F), vector(0.0F, 1.0F))
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("중복된 Vector ID입니다: duplicate");
    }

    @Test
    @DisplayName("Benchmark 설정은 직접 생성해도 URL과 실행 횟수 불변식을 검증한다")
    void benchmarkConfiguration_validatesEveryConstructionPath() {
        assertThatThrownBy(() -> new BenchmarkConfiguration(
            URI.create("ftp://localhost:8000"),
            1,
            2,
            32,
            Path.of("result.json")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Embedding Server URL은 HTTP 또는 HTTPS여야 합니다.");

        assertThatThrownBy(() -> new BenchmarkConfiguration(
            URI.create("http://localhost:8000"),
            0,
            2,
            32,
            Path.of("result.json")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Warm-up과 측정 Round는 각각 1 이상이어야 합니다.");
    }

    private void assertBoundaryCoverage(
        List<QueryCase> corpus,
        ChunkProfile profile,
        int targetBoundary,
        boolean expected
    ) {
        List<QueryCase> targetCases = corpus.stream()
            .filter(queryCase -> queryCase.targetBoundary() == targetBoundary)
            .toList();
        ChunkedCorpus chunked = ChunkQualityBenchmarkSupport.chunk(targetCases, profile);

        for (QueryCase queryCase : targetCases) {
            boolean covered = chunked.candidates().stream()
                .anyMatch(candidate -> ChunkQualityBenchmarkSupport.isRelevant(queryCase, candidate));
            assertThat(covered).isEqualTo(expected);
        }
    }

    private QueryCase queryCase(String queryId, String documentId, int evidenceStart, int evidenceEnd) {
        return new QueryCase(
            queryId,
            documentId,
            "질문",
            "본문",
            "근거",
            evidenceStart,
            evidenceEnd,
            100
        );
    }

    private ChunkCandidate candidate(
        String candidateId,
        String documentId,
        int chunkIndex,
        int charStart,
        int charEnd
    ) {
        return new ChunkCandidate(candidateId, documentId, chunkIndex, "본문", charStart, charEnd);
    }

    private float[] vector(float first, float second) {
        float[] vector = new float[ChunkQualityBenchmarkSupport.VECTOR_DIMENSION];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }
}
