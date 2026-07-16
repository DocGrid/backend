package com.opensource.docgrid.domain.document.service.command;

import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.document.storage.StoredFile;

public record DocumentVersionUploadCommand(
    Long userId,
    Long documentId,
    ValidatedFile validatedFile,
    String fileHash,
    Long existingFileObjectId,
    StoredFile storedFile
) {
}
