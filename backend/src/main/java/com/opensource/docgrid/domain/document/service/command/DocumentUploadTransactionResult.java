package com.opensource.docgrid.domain.document.service.command;

import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;

/**
 * 최초 문서 업로드 응답과 이번 요청이 외부 저장소 후보 Object의 정리 책임을 획득했는지 함께 반환한다.
 */
public record DocumentUploadTransactionResult(
    DocumentUploadResponse response,
    boolean candidateClaimed
) {
}
