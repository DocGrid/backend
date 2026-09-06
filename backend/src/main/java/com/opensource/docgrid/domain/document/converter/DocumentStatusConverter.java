package com.opensource.docgrid.domain.document.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.dto.response.CurrentVersionStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.ProcessingVersionStatusResponse;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentStatusProjection;

/**
 * 문서 상태 Projection을 현재 검색 Version과 처리 중 Version이 구분된 API 응답으로 변환한다.
 *
 * <p>현재 Version은 실제 검색 가능한 INDEXED 상태일 때만 노출하고, 별도로 진행 중인 Version과 Job은
 * processingVersion에 담아 기존 검색 가능 Version과 혼동하지 않게 한다.
 */
@Component
public class DocumentStatusConverter {

    /** 문서 상태 조회 Projection을 공개 응답 계약으로 변환한다. */
    public DocumentStatusResponse toResponse(DocumentStatusProjection projection) {
        // 1. 현재 Version은 INDEXED가 확정된 경우에만 검색 기준 Version으로 응답한다.
        CurrentVersionStatusResponse currentVersion = null;
        if (projection.getCurrentVersionStatus() == DocumentVersionStatus.INDEXED) {
            currentVersion = new CurrentVersionStatusResponse(
                projection.getCurrentVersionNo(),
                projection.getCurrentVersionStatus()
            );
        }

        // 2. 업로드·인덱싱 중인 별도 Version이 있으면 Job 상태와 함께 처리 상태로 구성한다.
        ProcessingVersionStatusResponse processingVersion = null;
        if (projection.getProcessingVersionNo() != null) {
            processingVersion = new ProcessingVersionStatusResponse(
                projection.getProcessingVersionNo(),
                projection.getProcessingVersionStatus(),
                projection.getProcessingJobStatus()
            );
        }

        // 3. 문서 원장 상태와 두 Version 관점을 하나의 안정적인 상태 응답으로 조합한다.
        return new DocumentStatusResponse(
            projection.getDocumentId(),
            projection.getDocumentStatus(),
            currentVersion,
            processingVersion
        );
    }
}
