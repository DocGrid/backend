package com.opensource.docgrid.domain.document.service;

/**
 * 영속화 전 단계에서 계산된 문서 Chunk 한 건의 불변 값을 전달한다.
 *
 * <p>외부 파일 읽기와 Chunk 계산 구간이 JPA Entity나 Persistence Context에 의존하지 않도록
 * 저장에 필요한 값만 보관한다. 페이지·섹션·Metadata는 고정 크기 텍스트 Chunking 범위에서 비워 둔다.
 */
public record DocumentChunkDraft(
    int chunkIndex,
    String chunkText,
    int tokenCount,
    int charStart,
    int charEnd,
    Integer pageNo,
    String sectionTitle,
    String contentHash,
    String metadataJson
) {
}
