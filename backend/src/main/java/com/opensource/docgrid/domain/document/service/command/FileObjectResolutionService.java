package com.opensource.docgrid.domain.document.service.command;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.repository.FileObjectRepository;
import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 동일 바이너리의 FileObject 재사용과 동시 Insert 결과를 해석한다.
 * 현재 Adapter의 Provider·Bucket과 다른 저장 위치는 재사용하지 않아 DB와 실제 파일 저장소 경계를 지킨다.
 */
@Service
@RequiredArgsConstructor
public class FileObjectResolutionService {

    private final FileObjectRepository fileObjectRepository;
    private final FileStorageProperties fileStorageProperties;

    public Optional<Long> findReusableFileObjectId(String fileHash, long fileSize) {
        return fileObjectRepository.findByFileHashAndFileSize(fileHash, fileSize)
            .map(this::requireActiveLocation)
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
            // 1. 사전 조회된 FileObject도 Transaction 경계에서 현재 저장 위치와 다시 대조한다.
            FileObject existing = fileObjectRepository.findById(existingFileObjectId)
                .orElseThrow(() -> new DocGridException(ErrorCode.FILE_OBJECT_RESOLUTION_FAILED));
            return new Resolution(requireActiveLocation(existing), false);
        }
        if (storedFile == null) {
            throw new DocGridException(ErrorCode.FILE_OBJECT_RESOLUTION_FAILED);
        }

        // 2. 후보 위치를 포함해 Insert하고 동일 해시 경쟁에서는 기존 Row를 다시 조회한다.
        int inserted = fileObjectRepository.insertIfAbsent(
            storedFile.bucketName(), storedFile.objectKey(), validatedFile.originalFilename(),
            validatedFile.contentType(), validatedFile.fileSize(), fileHash,
            storedFile.storageProvider().name(), userId
        );
        FileObject fileObject = fileObjectRepository.findByFileHashAndFileSize(fileHash, validatedFile.fileSize())
            .orElseThrow(() -> new DocGridException(ErrorCode.FILE_OBJECT_RESOLUTION_FAILED));

        // 3. 다른 환경의 Row와 전역 중복 제약이 충돌했다면 후보를 재사용하지 않고 상위에서 정리하게 한다.
        requireStoredLocation(fileObject, storedFile);
        return new Resolution(fileObject, inserted == 1);
    }

    private FileObject requireActiveLocation(FileObject fileObject) {
        StorageProvider activeProvider = StorageProvider.valueOf(fileStorageProperties.getType().name());
        if (fileObject.getStorageProvider() == activeProvider
            && fileStorageProperties.getBucket().equals(fileObject.getBucketName())) {
            return fileObject;
        }
        throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
    }

    private void requireStoredLocation(FileObject fileObject, StoredFile storedFile) {
        if (fileObject.getStorageProvider() == storedFile.storageProvider()
            && fileObject.getBucketName().equals(storedFile.bucketName())) {
            return;
        }
        throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
    }

    /**
     * 선택된 FileObject와 새 후보가 실제 DB Row를 점유했는지를 함께 전달한다.
     */
    public record Resolution(FileObject fileObject, boolean candidateClaimed) {
    }
}
