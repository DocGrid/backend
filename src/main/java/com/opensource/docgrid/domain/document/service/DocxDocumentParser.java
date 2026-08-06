package com.opensource.docgrid.domain.document.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * Apache POI로 DOCX의 Heading, 본문 Paragraph와 Table을 원본 Body 순서대로 추출한다.
 *
 * <p>Heading을 Section 경계로 사용하지만 Header, Footer, Comment와 이미지 OCR은 처리하지 않는다.
 */
@Slf4j
@Component
public class DocxDocumentParser implements DocumentContentParser {

    private static final Set<DocumentType> SUPPORTED_TYPES = Set.of(DocumentType.DOCX);

    @Override
    public Set<DocumentType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * DOCX Body의 검색 가능한 Text를 Section Segment 목록으로 변환한다.
     */
    @Override
    public ParsedDocument parseDocument(byte[] content) {
        if (content == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
        }

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<ParsedDocumentSegment> segments = new ArrayList<>();
            List<String> sectionParts = new ArrayList<>();
            String sectionTitle = null;

            // 1. Paragraph와 Table을 종류별 목록으로 분리하지 않고 Body 요소의 원래 순서를 따른다.
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph paragraph) {
                    String paragraphText = canonicalize(paragraph.getText());
                    if (paragraphText.isBlank()) {
                        continue;
                    }

                    // 2. Heading은 이전 Section을 닫고 자신을 첫 줄로 포함하는 새 Section을 시작한다.
                    if (isHeading(paragraph)) {
                        addSegmentIfPresent(segments, sectionParts, sectionTitle);
                        sectionParts.clear();
                        sectionTitle = paragraphText;
                    }
                    sectionParts.add(paragraphText);
                } else if (bodyElement instanceof XWPFTable table) {
                    String tableText = tableText(table);
                    if (!tableText.isBlank()) {
                        sectionParts.add(tableText);
                    }
                }
            }

            // 3. 마지막 Section을 닫고 검색 가능한 본문이 없으면 안정적인 빈 문서 오류로 종료한다.
            addSegmentIfPresent(segments, sectionParts, sectionTitle);
            if (segments.isEmpty()) {
                throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_EMPTY);
            }
            return new ParsedDocument(segments);
        } catch (DocGridException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            log.error("DOCX 문서 구조를 읽지 못했습니다.", exception);
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED, exception);
        }
    }

    private boolean isHeading(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return false;
        }
        String normalizedStyle = style.toLowerCase(Locale.ROOT);
        return normalizedStyle.startsWith("heading") || normalizedStyle.equals("title");
    }

    private String tableText(XWPFTable table) {
        List<String> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = row.getTableCells().stream()
                .map(XWPFTableCell::getText)
                .map(this::canonicalize)
                .toList();
            String rowText = String.join("\t", cells);
            if (!rowText.isBlank()) {
                rows.add(rowText);
            }
        }
        return String.join("\n", rows);
    }

    private void addSegmentIfPresent(
        List<ParsedDocumentSegment> segments,
        List<String> sectionParts,
        String sectionTitle
    ) {
        if (sectionParts.isEmpty()) {
            return;
        }
        String text = String.join("\n", sectionParts);
        if (!text.isBlank()) {
            segments.add(new ParsedDocumentSegment(text, null, sectionTitle, null));
        }
    }

    private String canonicalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
