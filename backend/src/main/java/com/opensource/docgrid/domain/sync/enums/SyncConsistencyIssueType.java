package com.opensource.docgrid.domain.sync.enums;

/**
 * 문서 원장과 Job·Chunk·Vector 파생 데이터 사이에서 Reconciler가 탐지하는 불일치 유형이다.
 */
public enum SyncConsistencyIssueType {
    MISSING_JOB,
    MISSING_CHUNKS,
    MISSING_EMBEDDINGS,
    MODEL_MISMATCH,
    INVALID_CURRENT_VERSION,
    STALLED_VERSION,
    DELETED_DOCUMENT_RESIDUE,
    ORPHANED_DATA
}
