package com.opensource.docgrid.domain.document.service.command;

import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;

/**
 * 새 Version 업로드 응답과 이번 요청이 외부 저장소 후보 Object의 정리 책임을 획득했는지 함께 반환한다.
 */
public record DocumentVersionUploadTransactionResult(
    DocumentVersionUploadResponse response,
    boolean candidateClaimed
) {
}
