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

    /**
     * Spring이 제공한 Parser 후보를 문서 형식별 불변 Map으로 등록한다.
     *
     * <p>Parser가 지원 형식을 선언하지 않거나 같은 형식에 둘 이상 등록되면 애플리케이션 기동을 중단해
     * 배포 환경의 Bean 순서에 따라 Parser 선택이 달라지지 않게 한다.
     */
    public DocumentParserRegistry(List<DocumentContentParser> parserCandidates) {
        // 1. Enum Key 전용 Map에 각 Parser의 지원 형식을 하나씩 등록한다.
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

        // 2. 기동 이후 등록 내용이 바뀌지 않도록 불변 Map으로 고정한다.
        this.parsers = Map.copyOf(registered);
    }

    /**
     * 문서 형식에 맞는 Parser로 원본을 변환한다.
     */
    public ParsedDocument parse(DocumentType documentType, byte[] content) {
        // 1. 요청 문서 형식에 정확히 대응하는 Parser를 선택한다.
        DocumentContentParser parser = parsers.get(documentType);
        if (parser == null) {
            throw new DocGridException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
        }

        // 2. 선택한 Parser에 원본 변환을 위임하고 형식별 오류 계약을 그대로 전달한다.
        return parser.parseDocument(content);
    }
}
