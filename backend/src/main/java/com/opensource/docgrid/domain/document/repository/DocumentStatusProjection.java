package com.opensource.docgrid.domain.document.repository;

import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

/**
 * 문서 원장 상태와 현재 검색 Version 및 별도 처리 중 Version을 한 Query 결과로 받는 Projection이다.
 *
 * <p>Entity Graph를 로딩하지 않고 상태 API에 필요한 값만 조회하는 읽기 전용 경계다.
 */
public interface DocumentStatusProjection {

    Long getDocumentId();

    DocumentStatus getDocumentStatus();

    Integer getCurrentVersionNo();

    DocumentVersionStatus getCurrentVersionStatus();

    Integer getProcessingVersionNo();

    DocumentVersionStatus getProcessingVersionStatus();

    EmbeddingJobStatus getProcessingJobStatus();
}
