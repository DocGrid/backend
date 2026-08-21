package com.opensource.docgrid.domain.document.service.command;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.repository.FileObjectRepository;
import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FileObjectResolutionService {

    private final FileObjectRepository fileObjectRepository;

    public Optional<Long> findReusableFileObjectId(String fileHash, long fileSize) {
        return fileObjectRepository.findByFileHashAndFileSize(fileHash, fileSize)
            .map(FileObject::getId);
    }

    public Resolution resolve(
        Long userId,
        ValidatedFile validatedFile,
        String fileHash,
        Long existingFileObjectId,
        StoredFile storedFile
    ) {
        if (existingFileObjectId != null) {
            FileObject existing = fileObjectRepository.findById(existingFileObjectId)
                .orElseThrow(() -> new DocGridException(ErrorCode.FILE_OBJECT_RESOLUTION_FAILED));
            return new Resolution(existing, false);
        }
        if (storedFile == null) {
            throw new DocGridException(ErrorCode.FILE_OBJECT_RESOLUTION_FAILED);
        }

        int inserted = fileObjectRepository.insertIfAbsent(
            storedFile.bucketName(), storedFile.objectKey(), validatedFile.originalFilename(),
            validatedFile.contentType(), validatedFile.fileSize(), fileHash,
            storedFile.storageProvider().name(), userId
        );
        FileObject fileObject = fileObjectRepository.findByFileHashAndFileSize(fileHash, validatedFile.fileSize())
            .orElseThrow(() -> new DocGridException(ErrorCode.FILE_OBJECT_RESOLUTION_FAILED));
        return new Resolution(fileObject, inserted == 1);
    }

    public record Resolution(FileObject fileObject, boolean candidateClaimed) {
    }
}
