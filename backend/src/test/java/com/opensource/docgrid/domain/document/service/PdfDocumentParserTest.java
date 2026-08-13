package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * PDF Page별 Text, 암호화·손상 문서와 OCR 필요 판별 계약을 메모리 Fixture로 검증한다.
 */
@DisplayName("PdfDocumentParser 테스트")
class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    @DisplayName("각 PDF Page의 Text와 1부터 시작하는 Page Number를 보존한다")
    void parseDocument_extractsTextByPage() throws IOException {
        byte[] pdf = pdfWithPages("first page", "second page");

        ParsedDocument result = parser.parseDocument(pdf);

        assertThat(parser.supportedTypes()).containsExactly(DocumentType.PDF);
        assertThat(result.segments()).extracting(ParsedDocumentSegment::pageNo)
            .containsExactly(1, 2);
        assertThat(result.segments()).extracting(ParsedDocumentSegment::text)
            .containsExactly("first page", "second page");
    }

    @Test
    @DisplayName("빈 Page는 건너뛰고 이후 Text Page의 원래 번호를 보존한다")
    void parseDocument_skipsEmptyPageAndPreservesPageNumber() throws IOException {
        byte[] pdf = pdfWithPages(null, "searchable page");

        ParsedDocument result = parser.parseDocument(pdf);

        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.pageNo()).isEqualTo(2);
            assertThat(segment.text()).isEqualTo("searchable page");
        });
    }

    @Test
    @DisplayName("모든 Page에 Text가 없으면 OCR 필요 오류가 발생한다")
    void parseDocument_throwsWhenOcrIsRequired() throws IOException {
        assertError(pdfWithPages(null, null), ErrorCode.DOCUMENT_OCR_REQUIRED);
    }

    @Test
    @DisplayName("Page가 하나도 없는 PDF면 빈 문서 오류가 발생한다")
    void parseDocument_throwsWhenPdfHasNoPage() throws IOException {
        // Page 자체가 없으면 OCR로도 복구할 수 없으므로 스캔 PDF와 다른 오류로 구분해야 한다.
        assertError(pdfWithPages(), ErrorCode.DOCUMENT_CONTENT_EMPTY);
    }

    @Test
    @DisplayName("Password 보호 PDF면 암호화 문서 오류가 발생한다")
    void parseDocument_throwsWhenPdfIsEncrypted() throws IOException {
        assertError(encryptedPdf(), ErrorCode.DOCUMENT_PDF_ENCRYPTED);
    }

    @Test
    @DisplayName("손상된 PDF면 제한된 파싱 실패 오류가 발생한다")
    void parseDocument_throwsWhenPdfIsCorrupted() {
        assertError(new byte[] {1, 2, 3}, ErrorCode.DOCUMENT_PARSING_FAILED);
    }

    private byte[] pdfWithPages(String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (pageText != null) {
                    writeText(document, page, pageText);
                }
            }
            return save(document);
        }
    }

    private byte[] encryptedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            writeText(document, page, "protected");
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                "owner-password",
                "user-password",
                new AccessPermission()
            );
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            return save(document);
        }
    }

    private void writeText(PDDocument document, PDPage page, String text) throws IOException {
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
            contentStream.beginText();
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            contentStream.newLineAtOffset(72, 720);
            contentStream.showText(text);
            contentStream.endText();
        }
    }

    private byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }

    private void assertError(byte[] content, ErrorCode errorCode) {
        assertThatThrownBy(() -> parser.parseDocument(content))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
