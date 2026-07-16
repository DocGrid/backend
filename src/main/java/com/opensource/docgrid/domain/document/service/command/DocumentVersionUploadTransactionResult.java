package com.opensource.docgrid.domain.document.service.command;

import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;

public record DocumentVersionUploadTransactionResult(
    DocumentVersionUploadResponse response,
    boolean candidateClaimed
) {
}
