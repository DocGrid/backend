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

    public Long requiredLong(SyncOutboxEvent event, String fieldName) {
        JsonNode node = read(event).get(fieldName);
        if (node == null || !node.canConvertToLong() || node.longValue() <= 0) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
        return node.longValue();
    }

    public String requiredText(SyncOutboxEvent event, String fieldName) {
        JsonNode node = read(event).get(fieldName);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
        return node.textValue();
    }

    private JsonNode read(SyncOutboxEvent event) {
        try {
            JsonNode root = objectMapper.readTree(event.getPayloadJson());
            if (root == null || !root.isObject()) {
                throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
            }
            return root;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT, exception);
        }
    }
}
