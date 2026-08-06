package com.opensource.docgrid.domain.document.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.config.DocumentChunkingProperties;

import lombok.RequiredArgsConstructor;

/**
 * Canonical Text 또는 Page·Section Segment를 Unicode Code Point 기준 Chunk 목록으로 계산한다.
 *
 * <p>DB와 외부 저장소를 사용하지 않는 순수 계산 경계이며, 같은 입력과 설정에 대해 Index,
 * 전역 반열린 범위, 출처 Metadata, Token 추정치와 SHA-256 Hash가 항상 같은 Draft 목록을 만든다.
 */
@Component
@RequiredArgsConstructor
public class FixedSizeChunker {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final DocumentChunkingProperties properties;

    /**
     * Canonical Text를 저장 가능한 Chunk Draft 목록으로 나눈다.
     *
     * @param canonicalText 파싱과 정규화가 끝난 Text
     * @return 0부터 연속된 Index를 가진 Chunk Draft 목록
     * @throws IllegalArgumentException Chunk 크기와 Overlap 조합이 유효하지 않은 경우
     */
    public List<DocumentChunkDraft> chunk(String canonicalText) {
        ChunkSettings settings = validatedSettings();
        List<DocumentChunkDraft> drafts = new ArrayList<>();
        appendSegmentChunks(drafts, canonicalText, 0, null, null, null, settings);
        return List.copyOf(drafts);
    }

    /**
     * Page·Section Segment 경계를 넘지 않게 문서 전체 Chunk Draft를 계산한다.
     *
     * @param parsedDocument 문서 순서와 출처 Metadata가 고정된 파싱 결과
     * @return 문서 전체에서 연속 Index와 전역 Character Offset을 가진 Chunk Draft 목록
     */
    public List<DocumentChunkDraft> chunk(ParsedDocument parsedDocument) {
        ChunkSettings settings = validatedSettings();
        List<DocumentChunkDraft> drafts = new ArrayList<>();
        int globalOffset = 0;

        // 1. Segment를 따로 Chunking해 PDF Page와 DOCX Section 경계가 한 Chunk에 섞이지 않게 한다.
        for (int index = 0; index < parsedDocument.segments().size(); index++) {
            ParsedDocumentSegment segment = parsedDocument.segments().get(index);
            appendSegmentChunks(
                drafts,
                segment.text(),
                globalOffset,
                segment.pageNo(),
                segment.sectionTitle(),
                segment.metadataJson(),
                settings
            );

            // 2. 개념적 Canonical Text에서 Segment 사이 LF 한 개를 포함해 전역 Offset을 계산한다.
            globalOffset += segment.text().codePointCount(0, segment.text().length());
            if (index < parsedDocument.segments().size() - 1) {
                globalOffset++;
            }
        }
        return List.copyOf(drafts);
    }

    private void appendSegmentChunks(
        List<DocumentChunkDraft> drafts,
        String segmentText,
        int globalOffset,
        Integer pageNo,
        String sectionTitle,
        String metadataJson,
        ChunkSettings settings
    ) {
        int[] codePoints = segmentText.codePoints().toArray();
        if (codePoints.length == 0) {
            return;
        }

        int step = settings.chunkSize() - settings.overlap();

        // 3. 시작 위치를 Code Point 단위로 이동해 Surrogate Pair 중간 분할을 방지한다.
        for (int start = 0; start < codePoints.length; start += step) {
            int end = Math.min(start + settings.chunkSize(), codePoints.length);
            String chunkText = new String(codePoints, start, end - start);

            // 4. 문서 전체 Index·Offset과 Segment 출처 Metadata를 같은 Draft에 고정한다.
            drafts.add(new DocumentChunkDraft(
                drafts.size(),
                chunkText,
                estimateTokenCount(chunkText),
                globalOffset + start,
                globalOffset + end,
                pageNo,
                sectionTitle,
                sha256(chunkText),
                metadataJson
            ));

            // 5. 마지막 Chunk가 원문 끝에 도달하면 Overlap만 남은 추가 Chunk를 만들지 않는다.
            if (end == codePoints.length) {
                break;
            }
        }
    }

    private ChunkSettings validatedSettings() {
        int chunkSize = properties.getChunkSize();
        int overlap = properties.getOverlap();
        // 설정 객체가 생성 후 변경되더라도 시작 위치가 반드시 앞으로 이동하게 불변식을 재검증한다.
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("Chunk 크기는 양수이고 Overlap은 0 이상 Chunk 크기 미만이어야 합니다.");
        }
        return new ChunkSettings(chunkSize, overlap);
    }

    private int estimateTokenCount(String text) {
        int tokenCount = 0;
        boolean insideToken = false;
        int[] codePoints = text.codePoints().toArray();

        for (int codePoint : codePoints) {
            if (Character.isWhitespace(codePoint)) {
                insideToken = false;
            } else if (!insideToken) {
                tokenCount++;
                insideToken = true;
            }
        }
        return tokenCount;
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    /**
     * 한 번의 Chunk 계산에서 재검증된 크기와 Overlap Snapshot이다.
     */
    private record ChunkSettings(int chunkSize, int overlap) {
    }
}
