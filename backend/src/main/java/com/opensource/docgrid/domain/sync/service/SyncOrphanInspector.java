package com.opensource.docgrid.domain.sync.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;

import lombok.RequiredArgsConstructor;

/**
 * FK 우회나 부분 복구로 원장 관계를 잃은 Chunk·Embedding 행을 전역 읽기 검사로 탐지한다.
 */
@Component
@RequiredArgsConstructor
public class SyncOrphanInspector {

    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingRepository embeddingRepository;

    public List<SyncConsistencyObservation> inspect() {
        long orphanChunkCount = documentChunkRepository.countOrphanedRows();
        long orphanEmbeddingCount = embeddingRepository.countOrphanedRows();
        if (orphanChunkCount == 0 && orphanEmbeddingCount == 0) {
            return List.of();
        }
        return List.of(new SyncConsistencyObservation(
            "ORPHANED_DATA:GLOBAL",
            SyncConsistencyIssueType.ORPHANED_DATA,
            SyncConsistencySeverity.CRITICAL,
            null,
            null,
            null,
            "{\"orphanChunkCount\":0,\"orphanEmbeddingCount\":0}",
            "{\"orphanChunkCount\":%d,\"orphanEmbeddingCount\":%d}"
                .formatted(orphanChunkCount, orphanEmbeddingCount),
            false
        ));
    }
}
