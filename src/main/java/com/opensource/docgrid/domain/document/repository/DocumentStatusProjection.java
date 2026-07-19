package com.opensource.docgrid.domain.document.repository;

import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

public interface DocumentStatusProjection {

    Long getDocumentId();

    DocumentStatus getDocumentStatus();

    Integer getCurrentVersionNo();

    DocumentVersionStatus getCurrentVersionStatus();

    Integer getProcessingVersionNo();

    DocumentVersionStatus getProcessingVersionStatus();

    EmbeddingJobStatus getProcessingJobStatus();
}
