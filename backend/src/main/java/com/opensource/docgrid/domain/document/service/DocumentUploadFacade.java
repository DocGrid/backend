package com.opensource.docgrid.domain.document.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.service.command.DocumentUploadCommand;
import com.opensource.docgrid.domain.document.service.command.DocumentUploadService;
import com.opensource.docgrid.domain.document.service.command.DocumentUploadTransactionResult;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadFacade {

    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final FileStorageService fileStorageService;
    private final DocumentUploadService documentUploadService;

    public DocumentUploadResponse upload(Long userId, DocumentUploadRequest request) {
        ValidatedFile validatedFile = fileValidationService.validate(request.file());
        String fileHash = fileHashService.calculateSha256(request.file());
        Optional<Long> existingFileObjectId = documentUploadService.findReusableFileObjectId(
            fileHash, validatedFile.fileSize()
        );

        if (existingFileObjectId.isPresent()) {
            return documentUploadService.upload(createCommand(
                userId, request, validatedFile, fileHash, existingFileObjectId.get(), null
            )).response();
        }

        StoredFile candidate = storeCandidate(request, validatedFile);
        DocumentUploadTransactionResult result;
        try {
            result = documentUploadService.upload(createCommand(
                userId, request, validatedFile, fileHash, null, candidate
            ));
        } catch (RuntimeException e) {
            cleanupCandidate(candidate);
            throw e;
        }

        if (!result.candidateClaimed()) {
            cleanupCandidate(candidate);
        }
        return result.response();
    }

    private StoredFile storeCandidate(DocumentUploadRequest request, ValidatedFile validatedFile) {
        String objectKey = "documents/%s/%s.%s".formatted(
            UUID.randomUUID(), UUID.randomUUID(), validatedFile.extension()
        );
        try (InputStream inputStream = request.file().getInputStream()) {
            return fileStorageService.store(
                inputStream,
                validatedFile.fileSize(),
                validatedFile.contentType(),
                objectKey
            );
        } catch (IOException e) {
            log.error("파일 저장소 업로드용 원본 Stream을 열지 못했습니다.", e);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, e);
        }
    }

    private DocumentUploadCommand createCommand(
        Long userId,
        DocumentUploadRequest request,
        ValidatedFile validatedFile,
        String fileHash,
        Long existingFileObjectId,
        StoredFile storedFile
    ) {
        return new DocumentUploadCommand(
            userId,
            request.title(),
            request.description(),
            request.visibility(),
            validatedFile,
            fileHash,
            existingFileObjectId,
            storedFile
        );
    }

    private void cleanupCandidate(StoredFile candidate) {
        try {
            fileStorageService.delete(candidate);
        } catch (RuntimeException cleanupException) {
            log.error("사용되지 않은 저장소 Object 정리에 실패했습니다. bucket={}, objectKey={}",
                candidate.bucketName(), candidate.objectKey(), cleanupException);
        }
    }
}
