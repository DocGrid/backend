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

    /**
     * 원장 DocumentVersion 또는 Chunk 관계를 잃은 전역 행 수를 검사한다.
     *
     * @return 고아 데이터가 없으면 빈 목록, 있으면 자동 복구 불가능한 단일 CRITICAL 관찰 결과
     */
    public List<SyncConsistencyObservation> inspect() {
        // 1. FK를 우회해 남을 수 있는 Chunk와 Embedding 고아 행을 각각 집계한다.
        long orphanChunkCount = documentChunkRepository.countOrphanedRows();
        long orphanEmbeddingCount = embeddingRepository.countOrphanedRows();

        // 2. 두 집계가 모두 0이면 기존 전역 Issue를 해결할 수 있도록 빈 관찰 결과를 반환한다.
        if (orphanChunkCount == 0 && orphanEmbeddingCount == 0) {
            return List.of();
        }

        // 3. 물리 데이터 삭제가 필요한 문제는 자동 복구하지 않고 기대·실제 집계와 함께 운영 Issue로 만든다.
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
