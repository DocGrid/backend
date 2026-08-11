package com.opensource.docgrid.domain.document.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.opensource.docgrid.domain.document.config.DocumentChunkingProperties;
import com.opensource.docgrid.domain.document.service.DocumentChunkDraft;
import com.opensource.docgrid.domain.document.service.FixedSizeChunker;

/**
 * Chunk Size·Overlap Benchmark의 결정적 Corpus, Ground Truth와 품질·비용 계산 계약을 제공한다.
 *
 * <p>실제 모델이나 파일·DB I/O를 사용하지 않는 순수 계산 경계다. 외부 Benchmark와 일반 단위
 * 테스트가 같은 입력과 Relevant 판정, Exact Cosine 순위를 공유하게 해 실측 해석의 변형을 막는다.
 */
final class ChunkQualityBenchmarkSupport {

    static final int VECTOR_DIMENSION = 1024;
    static final int DOCUMENT_LENGTH = 2_200;
    static final int TOP_K = 10;
    private static final int EVIDENCE_BOUNDARY_OFFSET = 45;

    private static final int[] BOUNDARIES = {400, 800, 1_000, 1_600};
    private static final String[] PROJECTS = {
        "해오름", "은하수", "푸른샘", "노을빛", "별무리", "새벽길",
        "가람", "미리내", "솔바람", "구름재", "달맞이", "바다숲"
    };
    private static final String[] MARKERS = {
        "청록색 솔방울", "자주색 나침반", "황금색 종이학", "은색 모래시계",
        "주황색 풍차", "남색 조약돌", "연두색 등대", "분홍색 우산",
        "하늘색 책갈피", "갈색 망원경", "보라색 연", "초록색 종"
    };
    private static final String[] OWNERS = {
        "세림", "도윤", "하린", "지후", "예린", "수현",
        "태오", "민서", "유진", "현우", "서아", "준호"
    };
    private static final String FILLER =
        "이 문단은 일반적인 시스템 운영 배경과 절차를 설명한다. 기준 정보는 별도 문장에 기록된다. ";

    private ChunkQualityBenchmarkSupport() {
    }

    /**
     * 비교 대상 8개 Profile을 작은 Chunk부터 결정적인 순서로 반환한다.
     */
    static List<ChunkProfile> profiles() {
        return List.of(
            new ChunkProfile("c400-o0", 400, 0),
            new ChunkProfile("c400-o80", 400, 80),
            new ChunkProfile("c800-o0", 800, 0),
            new ChunkProfile("c800-o160", 800, 160),
            new ChunkProfile("c1000-o0", 1_000, 0),
            new ChunkProfile("c1000-o200", 1_000, 200),
            new ChunkProfile("c1600-o0", 1_600, 0),
            new ChunkProfile("c1600-o320", 1_600, 320)
        );
    }

    /**
     * 네 Chunk 경계마다 3개의 고유한 한국어 근거 문서·질의를 생성한다.
     */
    static List<QueryCase> createCorpus() {
        List<QueryCase> corpus = new ArrayList<>();
        int caseIndex = 0;

        // 1. 각 비교 Chunk 크기의 첫 경계 주변에 같은 수의 Case를 배치한다.
        for (int boundary : BOUNDARIES) {
            for (int repetition = 0; repetition < 3; repetition++) {
                String project = PROJECTS[caseIndex];
                String marker = MARKERS[caseIndex];
                String owner = OWNERS[caseIndex];
                String code = "DG-147-" + (char) ('A' + caseIndex);
                int intervalSeconds = 31 + caseIndex * 2;
                String evidence = "프로젝트 " + project + "의 장애 복구 표식은 " + marker
                    + "이고 승인 코드는 " + code + "이다. 야간 복구 담당자는 " + owner
                    + "이며 상태 확인 간격은 " + intervalSeconds + "초다.";
                String question = "프로젝트 " + project
                    + "의 승인 코드, 야간 복구 담당자와 상태 확인 간격은 무엇인가?";
                int evidenceStart = boundary - EVIDENCE_BOUNDARY_OFFSET;

                // 2. 근거 시작 Offset을 먼저 고정한 뒤 문서 길이를 정확히 맞춰 경계 조건을 보존한다.
                String text = padToCodePointLength(FILLER, evidenceStart)
                    + evidence
                    + padToCodePointLength(FILLER, DOCUMENT_LENGTH - evidenceStart - codePointLength(evidence));
                corpus.add(new QueryCase(
                    "q" + (caseIndex + 1),
                    "doc" + (caseIndex + 1),
                    question,
                    text,
                    evidence,
                    evidenceStart,
                    evidenceStart + codePointLength(evidence),
                    boundary
                ));
                caseIndex++;
            }
        }
        return List.copyOf(corpus);
    }

    /**
     * 제품 FixedSizeChunker를 주어진 Profile로 실행해 검색 후보와 비용을 계산한다.
     */
    static ChunkedCorpus chunk(List<QueryCase> corpus, ChunkProfile profile) {
        DocumentChunkingProperties properties = new DocumentChunkingProperties();
        properties.setChunkSize(profile.chunkSize());
        properties.setOverlap(profile.overlap());
        FixedSizeChunker chunker = new FixedSizeChunker(properties);
        List<ChunkCandidate> candidates = new ArrayList<>();
        long originalCodePoints = 0L;
        long chunkCodePoints = 0L;

        // 1. 실제 제품 Chunker를 문서별로 실행해 Offset과 Chunk Text를 그대로 사용한다.
        for (QueryCase queryCase : corpus) {
            originalCodePoints += codePointLength(queryCase.documentText());
            List<DocumentChunkDraft> drafts = chunker.chunk(queryCase.documentText());
            for (DocumentChunkDraft draft : drafts) {
                int length = codePointLength(draft.chunkText());
                chunkCodePoints += length;
                candidates.add(new ChunkCandidate(
                    queryCase.documentId() + ":" + draft.chunkIndex(),
                    queryCase.documentId(),
                    draft.chunkIndex(),
                    draft.chunkText(),
                    draft.charStart(),
                    draft.charEnd()
                ));
            }
        }

        long duplicateCodePoints = chunkCodePoints - originalCodePoints;
        double duplicateRatio = originalCodePoints == 0L
            ? 0.0
            : (double) duplicateCodePoints / originalCodePoints;
        return new ChunkedCorpus(
            List.copyOf(candidates),
            originalCodePoints,
            chunkCodePoints,
            duplicateCodePoints,
            duplicateRatio
        );
    }

    /**
     * 실제 Vector의 Exact Cosine 순위와 완전한 근거 범위로 검색 품질을 계산한다.
     */
    static QualityMetrics evaluate(
        List<QueryCase> corpus,
        List<ChunkCandidate> candidates,
        Map<String, float[]> queryVectors,
        Map<String, float[]> chunkVectors
    ) {
        int coveredQueries = 0;
        int hitAt1 = 0;
        int hitAt3 = 0;
        double reciprocalRankSum = 0.0;
        List<QueryQualityResult> queryResults = new ArrayList<>();

        for (QueryCase queryCase : corpus) {
            List<RankedChunk> rankedChunks = rank(
                candidates,
                requiredVector(queryVectors, queryCase.queryId()),
                chunkVectors
            );
            boolean covered = candidates.stream().anyMatch(candidate -> isRelevant(queryCase, candidate));
            if (covered) {
                coveredQueries++;
            }

            Integer firstRelevantRank = null;
            for (int index = 0; index < rankedChunks.size(); index++) {
                if (isRelevant(queryCase, rankedChunks.get(index).candidate())) {
                    firstRelevantRank = index + 1;
                    break;
                }
            }
            if (firstRelevantRank != null && firstRelevantRank == 1) {
                hitAt1++;
            }
            if (firstRelevantRank != null && firstRelevantRank <= 3) {
                hitAt3++;
            }
            if (firstRelevantRank != null && firstRelevantRank <= TOP_K) {
                reciprocalRankSum += 1.0 / firstRelevantRank;
            }

            RankedChunk top = rankedChunks.get(0);
            queryResults.add(new QueryQualityResult(
                queryCase.queryId(),
                covered,
                firstRelevantRank,
                top.candidate().candidateId(),
                top.similarity()
            ));
        }

        int queryCount = corpus.size();
        return new QualityMetrics(
            ratio(coveredQueries, queryCount),
            ratio(hitAt1, queryCount),
            ratio(hitAt3, queryCount),
            reciprocalRankSum / queryCount,
            List.copyOf(queryResults)
        );
    }

    /**
     * Vector 차원과 유한값, 0이 아닌 Norm을 검증한다.
     */
    static void validateVector(float[] vector) {
        validatedNorm(vector);
    }

    private static double validatedNorm(float[] vector) {
        if (vector == null || vector.length != VECTOR_DIMENSION) {
            throw new IllegalArgumentException("Embedding Vector는 1024차원이어야 합니다.");
        }
        double squaredNorm = 0.0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding Vector는 유한값만 포함해야 합니다.");
            }
            squaredNorm += value * value;
        }
        if (squaredNorm == 0.0) {
            throw new IllegalArgumentException("Embedding Vector Norm은 0보다 커야 합니다.");
        }
        return Math.sqrt(squaredNorm);
    }

    /**
     * 같은 차원의 두 Dense Vector 사이 Cosine Similarity를 계산한다.
     */
    static double cosineSimilarity(float[] left, float[] right) {
        return cosineSimilarity(left, validatedNorm(left), right);
    }

    private static double cosineSimilarity(float[] left, double leftNorm, float[] right) {
        double rightNorm = validatedNorm(right);
        double dotProduct = 0.0;
        for (int index = 0; index < left.length; index++) {
            dotProduct += left[index] * right[index];
        }
        return dotProduct / (leftNorm * rightNorm);
    }

    /**
     * Millisecond 표본의 Nearest-rank Median과 p95를 계산한다.
     */
    static TimingSummary summarizeTimings(List<Double> samplesMillis) {
        if (samplesMillis == null || samplesMillis.isEmpty()) {
            throw new IllegalArgumentException("Timing 표본은 한 개 이상이어야 합니다.");
        }
        if (samplesMillis.stream().anyMatch(value -> value == null || !Double.isFinite(value) || value < 0.0)) {
            throw new IllegalArgumentException("Timing 표본은 0 이상의 유한값이어야 합니다.");
        }
        List<Double> sorted = samplesMillis.stream().sorted().toList();
        return new TimingSummary(
            sorted.size(),
            nearestRank(sorted, 0.50),
            nearestRank(sorted, 0.95),
            sorted.get(sorted.size() - 1)
        );
    }

    private static List<RankedChunk> rank(
        List<ChunkCandidate> candidates,
        float[] queryVector,
        Map<String, float[]> chunkVectors
    ) {
        double queryNorm = validatedNorm(queryVector);
        List<RankedChunk> ranked = new ArrayList<>(candidates.size());
        for (ChunkCandidate candidate : candidates) {
            ranked.add(new RankedChunk(
                candidate,
                cosineSimilarity(
                    queryVector,
                    queryNorm,
                    requiredVector(chunkVectors, candidate.candidateId())
                )
            ));
        }
        ranked.sort(
            Comparator.comparingDouble(RankedChunk::similarity).reversed()
                .thenComparing(result -> result.candidate().documentId())
                .thenComparingInt(result -> result.candidate().chunkIndex())
        );
        return ranked;
    }

    static boolean isRelevant(QueryCase queryCase, ChunkCandidate candidate) {
        return queryCase.documentId().equals(candidate.documentId())
            && candidate.charStart() <= queryCase.evidenceStart()
            && candidate.charEnd() >= queryCase.evidenceEnd();
    }

    private static float[] requiredVector(Map<String, float[]> vectors, String key) {
        float[] vector = vectors.get(key);
        if (vector == null) {
            throw new IllegalArgumentException("Vector가 누락됐습니다: " + key);
        }
        return vector;
    }

    private static String padToCodePointLength(String pattern, int targetLength) {
        if (targetLength < 0) {
            throw new IllegalArgumentException("Padding 길이는 0 이상이어야 합니다.");
        }
        int[] patternCodePoints = pattern.codePoints().toArray();
        int[] result = new int[targetLength];
        for (int index = 0; index < targetLength; index++) {
            result[index] = patternCodePoints[index % patternCodePoints.length];
        }
        return new String(result, 0, result.length);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double nearestRank(List<Double> sorted, double percentile) {
        int rank = Math.max(1, (int) Math.ceil(percentile * sorted.size()));
        return sorted.get(rank - 1);
    }

    /**
     * 비교할 Chunk Size와 Overlap의 불변 조합이다.
     */
    record ChunkProfile(String profileId, int chunkSize, int overlap) {

        ChunkProfile {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("Profile ID는 비어 있을 수 없습니다.");
            }
            if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
                throw new IllegalArgumentException("Chunk Size·Overlap 조합이 유효하지 않습니다.");
            }
        }
    }

    /**
     * 한 Query의 문서 본문과 완전한 정답 근거 범위를 결합한 Ground Truth다.
     */
    record QueryCase(
        String queryId,
        String documentId,
        String question,
        String documentText,
        String evidence,
        int evidenceStart,
        int evidenceEnd,
        int targetBoundary
    ) {
    }

    /**
     * 제품 Chunker 결과에 검색 후보 식별자와 원문 범위를 결합한다.
     */
    record ChunkCandidate(
        String candidateId,
        String documentId,
        int chunkIndex,
        String text,
        int charStart,
        int charEnd
    ) {
    }

    /**
     * 한 Profile이 생성한 Chunk 목록과 결정적인 중복 비용을 보관한다.
     */
    record ChunkedCorpus(
        List<ChunkCandidate> candidates,
        long originalCodePoints,
        long chunkCodePoints,
        long duplicateCodePoints,
        double duplicateRatio
    ) {

        ChunkedCorpus {
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * 전체 Query의 Answer Coverage, Hit@K와 MRR 결과다.
     */
    record QualityMetrics(
        double answerCoverageRatio,
        double hitAt1,
        double hitAt3,
        double mrrAt10,
        List<QueryQualityResult> queries
    ) {

        QualityMetrics {
            queries = List.copyOf(queries);
        }
    }

    /**
     * 반복 실행 지연의 표본 수, Median, p95와 최댓값을 보관한다.
     */
    record TimingSummary(int sampleCount, double medianMillis, double p95Millis, double maxMillis) {
    }

    /**
     * 한 Query의 근거 보존 여부와 Exact 검색 결과를 설명한다.
     */
    record QueryQualityResult(
        String queryId,
        boolean answerCovered,
        Integer firstRelevantRank,
        String topCandidateId,
        double topSimilarity
    ) {
    }

    /**
     * Exact Ranking 내부에서 Chunk와 Cosine Similarity를 결합한다.
     */
    private record RankedChunk(ChunkCandidate candidate, double similarity) {
    }

    /**
     * Vector Map을 입력 순서대로 만들 때 사용하는 결정적 변환 Helper다.
     */
    static Map<String, float[]> vectorMap(List<String> ids, List<float[]> vectors) {
        if (ids.size() != vectors.size()) {
            throw new IllegalArgumentException("ID와 Vector 개수가 일치해야 합니다.");
        }
        Map<String, float[]> result = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            validateVector(vectors.get(index));
            if (result.put(ids.get(index), vectors.get(index).clone()) != null) {
                throw new IllegalArgumentException("중복된 Vector ID입니다: " + ids.get(index));
            }
        }
        return Map.copyOf(result);
    }
}
