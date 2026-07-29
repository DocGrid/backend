package com.opensource.docgrid.domain.document.service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * TXT와 Markdown 원본 Byte를 결정적인 Canonical UTF-8 Text로 변환한다.
 *
 * <p>저장소와 문서 형식 판단은 담당하지 않으며, 엄격한 UTF-8 Decode, 선두 BOM 제거,
 * 줄바꿈 정규화와 공백 전용 문서 거부만 수행한다. Markdown 문법과 그 밖의 공백은 보존한다.
 */
@Component
public class TextDocumentParser {

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /**
     * 원본 Byte를 Canonical Text로 변환한다.
     *
     * @param content TXT 또는 Markdown 원본 Byte
     * @return BOM과 줄바꿈만 정규화된 Text
     */
    public String parse(byte[] content) {
        // 1. 잘못된 Byte를 대체 문자로 숨기지 않도록 UTF-8 오류를 즉시 보고한다.
        String decoded = decodeStrictly(content);

        // 2. UTF-8 BOM은 문서 맨 앞 한 개만 제거하고 본문에 등장하는 동일 문자는 보존한다.
        String withoutBom = decoded.startsWith(String.valueOf(BYTE_ORDER_MARK))
            ? decoded.substring(1)
            : decoded;

        // 3. 플랫폼별 줄바꿈을 LF 하나로 통일하되 다른 공백과 빈 줄은 변경하지 않는다.
        String canonicalText = withoutBom.replace("\r\n", "\n").replace('\r', '\n');

        // 4. 검색 가능한 글자가 없는 문서는 Chunk를 만들지 않고 명시적인 입력 오류로 거부한다.
        if (canonicalText.isBlank()) {
            throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_EMPTY);
        }
        return canonicalText;
    }

    private String decodeStrictly(byte[] content) {
        if (content == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_TEXT_DECODING_FAILED);
        }

        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new DocGridException(ErrorCode.DOCUMENT_TEXT_DECODING_FAILED, exception);
        }
    }
}
