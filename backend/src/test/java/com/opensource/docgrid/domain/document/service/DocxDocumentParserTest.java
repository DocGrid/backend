package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * DOCX Heading·본문·Table의 문서 순서와 Section Title, 빈·손상 문서 오류를 검증한다.
 */
@DisplayName("DocxDocumentParser 테스트")
class DocxDocumentParserTest {

    private final DocxDocumentParser parser = new DocxDocumentParser();

    @Test
    @DisplayName("Heading 이전 본문과 Section별 본문·Table 순서를 보존한다")
    void parseDocument_extractsBodyElementsBySection() throws IOException {
        byte[] docx = structuredDocx();

        ParsedDocument result = parser.parseDocument(docx);

        assertThat(parser.supportedTypes()).containsExactly(DocumentType.DOCX);
        assertThat(result.segments()).hasSize(3);
        assertThat(result.segments().get(0).sectionTitle()).isNull();
        assertThat(result.segments().get(0).text()).isEqualTo("introduction");
        assertThat(result.segments().get(1).sectionTitle()).isEqualTo("Section A");
        assertThat(result.segments().get(1).text())
            .isEqualTo("Section A\nparagraph A\nA1\tA2\nB1\tB2");
        assertThat(result.segments().get(2).sectionTitle()).isEqualTo("Section B");
        assertThat(result.segments().get(2).text()).isEqualTo("Section B\nparagraph B");
    }

    @Test
    @DisplayName("검색 가능한 본문이 없는 DOCX면 빈 문서 오류가 발생한다")
    void parseDocument_throwsWhenDocumentIsEmpty() throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            assertError(save(document), ErrorCode.DOCUMENT_CONTENT_EMPTY);
        }
    }

    @Test
    @DisplayName("손상된 DOCX면 제한된 파싱 실패 오류가 발생한다")
    void parseDocument_throwsWhenDocumentIsCorrupted() {
        assertError(new byte[] {1, 2, 3}, ErrorCode.DOCUMENT_PARSING_FAILED);
    }

    private byte[] structuredDocx() throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("introduction");
            heading(document, "Section A");
            document.createParagraph().createRun().setText("paragraph A");

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("A1");
            table.getRow(0).getCell(1).setText("A2");
            table.getRow(1).getCell(0).setText("B1");
            table.getRow(1).getCell(1).setText("B2");

            heading(document, "Section B");
            document.createParagraph().createRun().setText("paragraph B");
            return save(document);
        }
    }

    private void heading(XWPFDocument document, String text) {
        XWPFParagraph heading = document.createParagraph();
        heading.setStyle("Heading1");
        heading.createRun().setText(text);
    }

    private byte[] save(XWPFDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(output);
        return output.toByteArray();
    }

    private void assertError(byte[] content, ErrorCode errorCode) {
        assertThatThrownBy(() -> parser.parseDocument(content))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
