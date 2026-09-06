package com.opensource.docgrid.domain.document.service.command;

import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.document.storage.StoredFile;

/**
 * 최초 문서 업로드 Transaction에 필요한 사용자·메타데이터와 파일 준비 결과를 묶는 내부 Command다.
 *
 * <p>Facade가 Transaction 밖에서 검증·Hash·저장한 결과를 전달하며, 기존 FileObject 재사용과 새 저장소
 * 후보 중 어떤 경로인지 식별할 값을 함께 보존한다.
 */
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
