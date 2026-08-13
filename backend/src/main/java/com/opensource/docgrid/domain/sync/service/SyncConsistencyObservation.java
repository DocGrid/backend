package com.opensource.docgrid.domain.sync.service;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;

/**
 * 한 번의 Version 검사에서 발견한 기대 상태와 실제 상태의 불일치 Snapshot이다.
 *
 * <p>Issue Service가 이 값을 멱등 Issue로 영속화하고, repairable 값이 true인 경우에만 Outbox 기반
 * 재인덱싱을 요청한다.
 */
public record SyncConsistencyObservation(
    String issueKey,
    SyncConsistencyIssueType issueType,
    SyncConsistencySeverity severity,
    Document document,
    DocumentVersion documentVersion,
    EmbeddingModel embeddingModel,
    String expectedJson,
    String actualJson,
    boolean repairable
) {
}
