package com.opensource.docgrid.domain.embedding.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opensource.docgrid.domain.document.config.DocumentChunkingProperties;
import com.opensource.docgrid.domain.document.service.DocumentChunkDraft;
import com.opensource.docgrid.domain.document.service.FixedSizeChunker;
import com.opensource.docgrid.domain.document.service.PdfDocumentParser;

/**
 * 외부 PDF 파일을 운영 Parser와 Chunker로 처리해 실제 임베딩 Benchmark 전용 Corpus를 만든다.
 *
 * <p>출력에는 원문 Chunk가 포함되므로 Git에서 제외된 {@code build/reports} 경로에만 저장한다.
 * 제품 DB와 Object Storage는 사용하지 않으며, Parser·Chunk 경계가 실제 인덱싱과 같은지만 보장한다.
 */
public final class RealPdfEmbeddingCorpusExporter {

    private static final String HASH_ALGORITHM = "SHA-256";

    private RealPdfEmbeddingCorpusExporter() {
    }

    /**
     * 첫 번째 인자를 출력 JSON, 나머지 인자를 입력 PDF 경로로 사용해 Corpus를 저장한다.
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("출력 경로와 한 개 이상의 PDF 경로가 필요합니다.");
        }

        Path outputPath = Path.of(args[0]).toAbsolutePath().normalize();
        List<Path> pdfPaths = Arrays.stream(args)
            .skip(1)
            .map(Path::of)
            .map(path -> path.toAbsolutePath().normalize())
            .toList();

        CorpusReport report = export(pdfPaths);
        Files.createDirectories(outputPath.getParent());
        new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .writeValue(outputPath.toFile(), report);
        System.out.printf("실제 PDF Corpus 생성 완료: documents=%d chunks=%d output=%s%n",
            report.documents().size(), report.totalChunkCount(), outputPath);
    }

    static CorpusReport export(List<Path> pdfPaths) throws IOException {
        if (pdfPaths == null || pdfPaths.isEmpty()) {
            throw new IllegalArgumentException("한 개 이상의 PDF 경로가 필요합니다.");
        }

        DocumentChunkingProperties properties = new DocumentChunkingProperties();
        PdfDocumentParser parser = new PdfDocumentParser();
        FixedSizeChunker chunker = new FixedSizeChunker(properties);

        // 1. 운영 Parser와 Chunker를 그대로 사용해 Benchmark 입력이 실제 인덱싱 경계를 재현하게 한다.
        List<CorpusDocument> documents = pdfPaths.stream()
            .map(path -> exportDocument(path, parser, chunker))
            .toList();

        // 2. 원문은 임시 JSON에만 두고 보고서에서 대조할 수 있는 파일·Chunk Hash를 함께 남긴다.
        return new CorpusReport(
            1,
            Instant.now().toString(),
            properties.getChunkSize(),
            properties.getOverlap(),
            documents
        );
    }

    private static CorpusDocument exportDocument(
        Path path,
        PdfDocumentParser parser,
        FixedSizeChunker chunker
    ) {
        try {
            byte[] content = Files.readAllBytes(path);
            List<DocumentChunkDraft> drafts = chunker.chunk(parser.parseDocument(content));
            List<CorpusChunk> chunks = drafts.stream()
                .map(draft -> new CorpusChunk(
                    draft.chunkIndex(),
                    draft.chunkText(),
                    draft.chunkText().codePointCount(0, draft.chunkText().length()),
                    draft.chunkText().getBytes(StandardCharsets.UTF_8).length,
                    draft.tokenCount(),
                    draft.contentHash()
                ))
                .toList();
            return new CorpusDocument(
                path.getFileName().toString(),
                content.length,
                sha256(content),
                chunks
            );
        } catch (IOException exception) {
            throw new IllegalStateException("PDF Corpus를 읽지 못했습니다: " + path, exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM).digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    /**
     * 한 번의 실제 PDF Corpus 생성 결과와 운영 Chunk 설정을 보존한다.
     */
    record CorpusReport(
        int schemaVersion,
        String generatedAt,
        int chunkSize,
        int overlap,
        List<CorpusDocument> documents
    ) {

        int totalChunkCount() {
            return documents.stream().mapToInt(document -> document.chunks().size()).sum();
        }
    }

    /**
     * 원본 PDF 식별 정보와 해당 파일에서 생성된 Chunk 순서를 보존한다.
     */
    record CorpusDocument(
        String sourceName,
        long sizeBytes,
        String fileSha256,
        List<CorpusChunk> chunks
    ) {
    }

    /**
     * 실제 Provider 입력 Text와 공개 결과에 사용할 길이·Hash 통계를 결합한다.
     */
    record CorpusChunk(
        int chunkIndex,
        String text,
        int codePointCount,
        int utf8Bytes,
        int estimatedTokenCount,
        String contentHash
    ) {
    }
}
