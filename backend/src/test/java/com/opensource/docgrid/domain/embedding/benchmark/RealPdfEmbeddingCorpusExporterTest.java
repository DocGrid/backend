package com.opensource.docgrid.domain.embedding.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 실제 PDF Corpus Exporter가 운영 Parser·Chunker의 길이와 Hash 계약을 유지하는지 검증한다.
 */
@DisplayName("실제 PDF Embedding Corpus Exporter 테스트")
class RealPdfEmbeddingCorpusExporterTest {

    @TempDir private Path tempDir;

    @Test
    @DisplayName("PDF를 운영 기본값으로 Chunking하고 원문·길이·Hash를 보존한다")
    void export_preservesProductionChunkContract() throws IOException {
        Path pdfPath = tempDir.resolve("benchmark.pdf");
        createPdf(pdfPath, 18);

        RealPdfEmbeddingCorpusExporter.CorpusReport report =
            RealPdfEmbeddingCorpusExporter.export(List.of(pdfPath));

        assertThat(report.schemaVersion()).isEqualTo(1);
        assertThat(report.chunkSize()).isEqualTo(1_000);
        assertThat(report.overlap()).isEqualTo(200);
        assertThat(report.documents()).hasSize(1);
        RealPdfEmbeddingCorpusExporter.CorpusDocument document = report.documents().get(0);
        assertThat(document.sourceName()).isEqualTo("benchmark.pdf");
        assertThat(document.sizeBytes()).isPositive();
        assertThat(document.fileSha256()).hasSize(64);
        assertThat(document.chunks()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(document.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.text()).isNotBlank();
            assertThat(chunk.codePointCount()).isBetween(1, 1_000);
            assertThat(chunk.utf8Bytes()).isPositive();
            assertThat(chunk.estimatedTokenCount()).isPositive();
            assertThat(chunk.contentHash()).hasSize(64);
        });
    }

    private void createPdf(Path path, int lineCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                stream.newLineAtOffset(40, 740);
                for (int line = 0; line < lineCount; line++) {
                    stream.showText("DocGrid embedding benchmark line " + line + " " + "x".repeat(60));
                    stream.newLineAtOffset(0, -18);
                }
                stream.endText();
            }
            document.save(path.toFile());
        }
    }
}
