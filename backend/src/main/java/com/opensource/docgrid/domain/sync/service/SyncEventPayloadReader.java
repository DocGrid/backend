package com.opensource.docgrid.domain.sync.service;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * Sync Event의 최소 JSON Payload를 검증해 Handler에 타입 안전한 값으로 제공한다.
 *
 * <p>Payload는 최신 도메인 상태가 아니라 조회에 필요한 힌트이므로, Handler는 이 값을 사용해 Entity를
 * 다시 조회하고 관계를 검증해야 한다.
 */
@Component
@RequiredArgsConstructor
public class SyncEventPayloadReader {

    private final ObjectMapper objectMapper;

    /**
     * Payload의 필수 양수 정수 필드를 읽는다.
     *
     * @throws DocGridException 필드가 없거나 양수 {@link Long}으로 변환할 수 없는 경우
     */
    public Long requiredLong(SyncOutboxEvent event, String fieldName) {
        // 1. 공통 JSON Object 검증을 거친 뒤 요청 필드를 찾는다.
        JsonNode node = read(event).get(fieldName);

        // 2. 식별자로 사용할 수 없는 누락·타입·범위 오류를 Event 정합성 문제로 통일한다.
        if (node == null || !node.canConvertToLong() || node.longValue() <= 0) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
        return node.longValue();
    }

    /**
     * Payload의 필수 비공백 문자열 필드를 읽는다.
     *
     * @throws DocGridException 필드가 없거나 비공백 문자열이 아닌 경우
     */
    public String requiredText(SyncOutboxEvent event, String fieldName) {
        // 1. 공통 JSON Object 검증을 거친 뒤 요청 필드를 찾는다.
        JsonNode node = read(event).get(fieldName);

        // 2. 누락·타입·공백 값은 Handler가 추측하지 않고 Event 정합성 오류로 처리한다.
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
        return node.textValue();
    }

    /**
     * Event Payload 문자열을 JSON Object로 파싱한다.
     */
    private JsonNode read(SyncOutboxEvent event) {
        try {
            // 1. 저장된 JSON을 Tree로 읽어 각 Handler가 별도 DTO 없이 최소 필드만 검증할 수 있게 한다.
            JsonNode root = objectMapper.readTree(event.getPayloadJson());

            // 2. null, 배열 또는 원시 값은 필드 기반 Event 계약을 만족하지 않으므로 거부한다.
            if (root == null || !root.isObject()) {
                throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
            }
            return root;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 3. 파싱 구현 예외는 외부에 노출하지 않고 일관된 정합성 오류로 변환한다.
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT, exception);
        }
    }
}
