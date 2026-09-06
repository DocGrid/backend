package com.opensource.docgrid.domain.embedding.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 문서 Embedding 생성과 저장 양쪽에서 사용하는 Vector 차원·유한 값 검증과 Hash 규칙을 제공한다.
 *
 * <p>Hash는 float 원소를 순서대로 big-endian IEEE 754 byte로 직렬화한 뒤 SHA-256을 적용한다.
 * 외부 호출 직후와 영속화 직전에 같은 규칙을 실행해 변경되거나 손상된 Draft 저장을 차단한다.
 */
public final class EmbeddingVectorSupport {

    /**
     * 상태를 갖지 않는 정적 Vector 규칙 클래스의 인스턴스 생성을 막는다.
     */
    private EmbeddingVectorSupport() {
    }

    /**
     * Vector가 Model 차원과 일치하고 모든 원소가 유한한지 검증한다.
     */
    public static void validate(float[] vector, int expectedDimension) {
        if (vector == null || vector.length != expectedDimension) {
            throw new DocGridException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new DocGridException(ErrorCode.EMBEDDING_VECTOR_INVALID);
            }
        }
    }

    /**
     * 검증된 Vector의 재현 가능한 SHA-256 Hash를 계산한다.
     */
    public static String calculateHash(float[] vector) {
        try {
            // 1. 모든 런타임에서 제공되는 SHA-256 Digest를 생성한다.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // 2. 플랫폼 Endian과 무관하게 각 float를 big-endian IEEE 754 Byte 순서로 직렬화한다.
            ByteBuffer buffer = ByteBuffer
                .allocate(vector.length * Float.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
            for (float value : vector) {
                buffer.putFloat(value);
            }

            // 3. 전체 Vector Byte를 한 번에 해시해 소문자 16진수 문자열로 반환한다.
            return HexFormat.of().formatHex(digest.digest(buffer.array()));
        } catch (NoSuchAlgorithmException exception) {
            throw new DocGridException(ErrorCode.EMBEDDING_VECTOR_INVALID, exception);
        }
    }
}
