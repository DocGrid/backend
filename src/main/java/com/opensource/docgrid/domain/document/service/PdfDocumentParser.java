package com.opensource.docgrid.domain.document.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * PDFBox로 텍스트 PDF를 Page별 Segment로 변환한다.
 *
 * <p>암호화 PDF와 검색 가능한 Text가 없는 PDF를 일반 손상 문서와 구분하며,
 * 이미지 OCR과 Chunk 계산은 담당하지 않는다.
 */
@Slf4j
@Component
public class PdfDocumentParser implements DocumentContentParser {

    private static final Set<DocumentType> SUPPORTED_TYPES = Set.of(DocumentType.PDF);

    @Override
    public Set<DocumentType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * PDF 원본을 비어 있지 않은 Page Text Segment 목록으로 변환한다.
     */
    @Override
    public ParsedDocument parseDocument(byte[] content) {
        if (content == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
        }

        try (PDDocument document = Loader.loadPDF(content)) {
            // 1. 열린 뒤에도 Encryption Flag를 확인해 Password 없이 열린 암호화 문서를 허용하지 않는다.
            if (document.isEncrypted()) {
                throw new DocGridException(ErrorCode.DOCUMENT_PDF_ENCRYPTED);
            }
            if (document.getNumberOfPages() == 0) {
                throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_EMPTY);
            }

            PDFTextStripper textStripper = new PDFTextStripper();
            List<ParsedDocumentSegment> segments = new ArrayList<>();

            // 2. 한 번에 한 Page만 추출해 Chunk가 Page 경계를 넘지 않게 출처를 고정한다.
            for (int pageNo = 1; pageNo <= document.getNumberOfPages(); pageNo++) {
                textStripper.setStartPage(pageNo);
                textStripper.setEndPage(pageNo);
                String pageText = canonicalize(textStripper.getText(document));
                if (!pageText.isBlank()) {
                    segments.add(new ParsedDocumentSegment(pageText, pageNo, null, null));
                }
            }

            // 3. Page는 있으나 전체 Text가 비면 스캔 PDF로 보고 후속 OCR 대상임을 명시한다.
            if (segments.isEmpty()) {
                throw new DocGridException(ErrorCode.DOCUMENT_OCR_REQUIRED);
            }
            return new ParsedDocument(segments);
        } catch (InvalidPasswordException exception) {
            throw new DocGridException(ErrorCode.DOCUMENT_PDF_ENCRYPTED, exception);
        } catch (DocGridException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            log.error("PDF 문서 구조를 읽지 못했습니다.", exception);
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED, exception);
        }
    }

    private String canonicalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
