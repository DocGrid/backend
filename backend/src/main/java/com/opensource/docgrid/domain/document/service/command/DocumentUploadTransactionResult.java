package com.opensource.docgrid.domain.document.service.command;

import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;

public record DocumentUploadTransactionResult(
    DocumentUploadResponse response,
    boolean candidateClaimed
) {
}
