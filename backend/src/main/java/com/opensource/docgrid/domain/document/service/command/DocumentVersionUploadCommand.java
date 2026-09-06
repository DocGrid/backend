package com.opensource.docgrid.domain.document.service.command;

import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.document.storage.StoredFile;

/**
 * 기존 문서의 새 Version 생성 Transaction에 필요한 사용자·문서와 파일 준비 결과를 묶는 내부 Command다.
 */
public record DocumentVersionUploadCommand(
    Long userId,
    Long documentId,
    ValidatedFile validatedFile,
    String fileHash,
    Long existingFileObjectId,
    StoredFile storedFile
) {
}
