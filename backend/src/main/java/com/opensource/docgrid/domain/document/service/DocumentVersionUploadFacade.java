package com.opensource.docgrid.domain.document.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.dto.request.DocumentVersionUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.service.command.DocumentVersionUploadCommand;
import com.opensource.docgrid.domain.document.service.command.DocumentVersionUploadService;
import com.opensource.docgrid.domain.document.service.command.DocumentVersionUploadTransactionResult;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentVersionUploadFacade {

    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final FileStorageService fileStorageService;
    private final DocumentVersionUploadService documentVersionUploadService;

    public DocumentVersionUploadResponse upload(
        Long userId,
        Long documentId,
        DocumentVersionUploadRequest request
    ) {
        ValidatedFile validatedFile = fileValidationService.validate(request.file());
        String fileHash = fileHashService.calculateSha256(request.file());
        Optional<Long> existingFileObjectId = documentVersionUploadService.prepare(
            userId, documentId, validatedFile, fileHash
        );

        if (existingFileObjectId.isPresent()) {
            return documentVersionUploadService.upload(createCommand(
                userId, documentId, validatedFile, fileHash, existingFileObjectId.get(), null
            )).response();
        }

        StoredFile candidate = storeCandidate(request, validatedFile);
        DocumentVersionUploadTransactionResult result;
        try {
            result = documentVersionUploadService.upload(createCommand(
                userId, documentId, validatedFile, fileHash, null, candidate
            ));
        } catch (RuntimeException exception) {
            cleanupCandidate(candidate);
            throw exception;
        }
        if (!result.candidateClaimed()) {
            cleanupCandidate(candidate);
        }
        return result.response();
    }

    private StoredFile storeCandidate(DocumentVersionUploadRequest request, ValidatedFile validatedFile) {
        String objectKey = "documents/%s/%s.%s".formatted(
            UUID.randomUUID(), UUID.randomUUID(), validatedFile.extension()
        );
        try (InputStream inputStream = request.file().getInputStream()) {
            return fileStorageService.store(
                inputStream, validatedFile.fileSize(), validatedFile.contentType(), objectKey
            );
        } catch (IOException exception) {
            log.error("파일 저장소 업로드용 원본 Stream을 열지 못했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    private DocumentVersionUploadCommand createCommand(
        Long userId,
        Long documentId,
        ValidatedFile validatedFile,
        String fileHash,
        Long existingFileObjectId,
        StoredFile storedFile
    ) {
        return new DocumentVersionUploadCommand(
            userId, documentId, validatedFile, fileHash, existingFileObjectId, storedFile
        );
    }

    private void cleanupCandidate(StoredFile candidate) {
        try {
            fileStorageService.delete(candidate);
        } catch (RuntimeException cleanupException) {
            log.error("사용되지 않은 저장소 Object 정리에 실패했습니다.", cleanupException);
        }
    }
}
