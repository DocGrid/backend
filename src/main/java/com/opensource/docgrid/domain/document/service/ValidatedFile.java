package com.opensource.docgrid.domain.document.service;

import com.opensource.docgrid.domain.document.enums.DocumentType;

public record ValidatedFile(
    String originalFilename,
    String extension,
    String contentType,
    long fileSize,
    DocumentType documentType
) {
}
