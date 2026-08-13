package com.opensource.docgrid.domain.document.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 문서 형식마다 정확히 하나의 Parser를 선택하는 등록소다.
 *
 * <p>중복 등록은 Application 기동 시 실패시키고, 미등록 형식은 안정적인 도메인 오류로 변환한다.
 */
@Component
public class DocumentParserRegistry {

    private final Map<DocumentType, DocumentContentParser> parsers;

    public DocumentParserRegistry(List<DocumentContentParser> parserCandidates) {
        EnumMap<DocumentType, DocumentContentParser> registered = new EnumMap<>(DocumentType.class);

        for (DocumentContentParser parser : parserCandidates) {
            Set<DocumentType> supportedTypes = parser.supportedTypes();
            if (supportedTypes == null
                || supportedTypes.isEmpty()
                || supportedTypes.stream().anyMatch(documentType -> documentType == null)) {
                throw new IllegalStateException("Document Parser는 지원 형식을 하나 이상 등록해야 합니다.");
            }
            for (DocumentType documentType : supportedTypes) {
                // 같은 형식을 어느 Parser가 처리할지 배포마다 달라지는 구성을 허용하지 않는다.
                if (registered.putIfAbsent(documentType, parser) != null) {
                    throw new IllegalStateException("Document Parser가 중복 등록되었습니다: " + documentType);
                }
            }
        }
        this.parsers = Map.copyOf(registered);
    }

    /**
     * 문서 형식에 맞는 Parser로 원본을 변환한다.
     */
    public ParsedDocument parse(DocumentType documentType, byte[] content) {
        DocumentContentParser parser = parsers.get(documentType);
        if (parser == null) {
            throw new DocGridException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
        }
        return parser.parseDocument(content);
    }
}
