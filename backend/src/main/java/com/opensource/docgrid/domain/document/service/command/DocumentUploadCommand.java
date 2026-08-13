package com.opensource.docgrid.domain.document.service.command;

import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.document.storage.StoredFile;

public record DocumentUploadCommand(
    Long userId,
    String title,
    String description,
    VisibilityType visibility,
    ValidatedFile validatedFile,
    String fileHash,
    Long existingFileObjectId,
    StoredFile storedFile
) {
}
