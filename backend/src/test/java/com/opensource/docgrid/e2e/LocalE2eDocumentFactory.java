package com.opensource.docgrid.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

/**
 * 로컬 전체 관통 E2E가 저장소 Fixture 파일 없이 TXT·Text PDF·DOCX 원본을 Memory에서 생성하게 한다.
 *
 * <p>파일 이름과 Content-Type을 함께 반환해 실제 Multipart 업로드 Validation과 Parser 선택 경계를
 * 통과시킨다. PDF는 OCR이 필요 없는 Text Layer를 포함한다.
 */
final class LocalE2eDocumentFactory {

    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private LocalE2eDocumentFactory() {
    }

    static DocumentPayload text(String fileName, String title, String content) {
        return new DocumentPayload(
            fileName,
            MediaType.TEXT_PLAIN,
            title,
            content.getBytes(StandardCharsets.UTF_8)
        );
    }

    static DocumentPayload pdf(String fileName, String title, String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                        12
                    );
                    contentStream.newLineAtOffset(72, 720);
                    contentStream.showText(pageText);
                    contentStream.endText();
                }
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return new DocumentPayload(fileName, MediaType.APPLICATION_PDF, title, output.toByteArray());
        }
    }

    static DocumentPayload docx(
        String fileName,
        String title,
        String heading,
        String body
    ) throws IOException {
        return docx(fileName, title, List.of(new DocumentSection(heading, body)));
    }

    static DocumentPayload docx(
        String fileName,
        String title,
        List<DocumentSection> sections
    ) throws IOException {
        if (sections == null || sections.isEmpty()) {
            throw new IllegalArgumentException("DOCX Section은 하나 이상이어야 합니다.");
        }

        try (XWPFDocument document = new XWPFDocument()) {
            for (DocumentSection section : sections) {
                XWPFParagraph headingParagraph = document.createParagraph();
                headingParagraph.setStyle("Heading1");
                headingParagraph.createRun().setText(section.heading());
                document.createParagraph().createRun().setText(section.body());
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return new DocumentPayload(fileName, DOCX_MEDIA_TYPE, title, output.toByteArray());
        }
    }

    static ByteArrayResource resource(DocumentPayload payload) {
        return new NamedByteArrayResource(payload.fileName(), payload.content());
    }

    /** Multipart Converter가 실제 파일 이름을 Content-Disposition에 기록하게 하는 Memory Resource다. */
    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String fileName;

        private NamedByteArrayResource(String fileName, byte[] content) {
            super(content);
            this.fileName = fileName;
        }

        @Override
        public String getFilename() {
            return fileName;
        }
    }

    /** 실제 Multipart 업로드에 필요한 원본 이름·Media Type·제목·Byte를 묶는다. */
    record DocumentPayload(
        String fileName,
        MediaType mediaType,
        String title,
        byte[] content
    ) {
    }

    /** DOCX Fixture의 Heading과 그 Heading에 속한 본문을 한 Section으로 표현한다. */
    record DocumentSection(String heading, String body) {

        DocumentSection {
            if (heading == null || heading.isBlank() || body == null || body.isBlank()) {
                throw new IllegalArgumentException("DOCX Section 제목과 본문은 비어 있을 수 없습니다.");
            }
        }
    }
}
