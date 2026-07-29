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
 * Canonical Text를 Unicode Code Point 기준의 고정 크기·중첩 Chunk 목록으로 계산한다.
 *
 * <p>DB와 외부 저장소를 사용하지 않는 순수 계산 경계이며, 같은 Text와 설정에 대해 Index,
 * 반열린 범위, Token 추정치와 SHA-256 Hash가 항상 같은 Draft 목록을 만든다.
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
     */
    public List<DocumentChunkDraft> chunk(String canonicalText) {
        int[] codePoints = canonicalText.codePoints().toArray();
        if (codePoints.length == 0) {
            return List.of();
        }

        int chunkSize = properties.getChunkSize();
        int step = chunkSize - properties.getOverlap();
        List<DocumentChunkDraft> drafts = new ArrayList<>();

        // 1. 시작 위치를 Code Point 단위로 이동해 Surrogate Pair 중간 분할을 방지한다.
        for (int start = 0, chunkIndex = 0; start < codePoints.length; start += step, chunkIndex++) {
            int end = Math.min(start + chunkSize, codePoints.length);
            String chunkText = new String(codePoints, start, end - start);

            // 2. Entity에 필요한 계산 값만 Draft에 담고 페이지·섹션·Metadata는 이번 범위에서 비워 둔다.
            drafts.add(new DocumentChunkDraft(
                chunkIndex,
                chunkText,
                estimateTokenCount(chunkText),
                start,
                end,
                null,
                null,
                sha256(chunkText),
                null
            ));

            // 3. 마지막 Chunk가 원문 끝에 도달하면 Overlap만 남은 추가 Chunk를 만들지 않는다.
            if (end == codePoints.length) {
                break;
            }
        }
        return List.copyOf(drafts);
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
}
