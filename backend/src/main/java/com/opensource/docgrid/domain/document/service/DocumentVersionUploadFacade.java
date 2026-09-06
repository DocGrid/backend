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

/**
 * 기존 문서의 새 버전 업로드에서 파일 저장소와 데이터베이스 작업을 조율한다.
 *
 * <p>권한·파일·문서 상태를 사전 검증해 불필요한 저장소 쓰기를 피하고, 재사용할 FileObject가
 * 없을 때만 새 바이너리 후보를 저장한다. 이후 데이터베이스 변경은
 * {@link DocumentVersionUploadService}에 위임하며, 트랜잭션 실패나 동시 중복 경쟁으로 사용되지 않은
 * 저장소 객체는 보상 삭제한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentVersionUploadFacade {

    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final FileStorageService fileStorageService;
    private final DocumentVersionUploadService documentVersionUploadService;

    /**
     * 기존 문서에 새 파일 버전을 업로드하고 인덱싱 Job을 생성한다.
     *
     * @param userId 새 버전을 등록하는 사용자 ID
     * @param documentId 대상 문서 ID
     * @param request 새 버전 파일을 담은 요청
     * @return 새 버전과 인덱싱 Job 상태
     */
    public DocumentVersionUploadResponse upload(
        Long userId,
        Long documentId,
        DocumentVersionUploadRequest request
    ) {
        // 1. 저장 전에 파일을 검증하고 동일 바이너리 확인용 해시를 계산한다.
        ValidatedFile validatedFile = fileValidationService.validate(request.file());
        String fileHash = fileHashService.calculateSha256(request.file());
        Optional<Long> existingFileObjectId = documentVersionUploadService.prepare(
            userId, documentId, validatedFile, fileHash
        );

        // 2. 같은 바이너리가 이미 있으면 물리 파일 저장 없이 기존 FileObject로 DB Transaction을 실행한다.
        if (existingFileObjectId.isPresent()) {
            return documentVersionUploadService.upload(createCommand(
                userId, documentId, validatedFile, fileHash, existingFileObjectId.get(), null
            )).response();
        }

        // 3. 재사용 대상이 없을 때만 저장소에 새 후보 파일을 만든다.
        StoredFile candidate = storeCandidate(request, validatedFile);
        DocumentVersionUploadTransactionResult result;

        // 4. DB Transaction이 실패하면 어느 버전에서도 참조하지 않는 후보를 보상 삭제한다.
        try {
            result = documentVersionUploadService.upload(createCommand(
                userId, documentId, validatedFile, fileHash, null, candidate
            ));
        } catch (RuntimeException exception) {
            cleanupCandidate(candidate);
            throw exception;
        }

        // 5. 동시 요청이 기존 FileObject를 점유했다면 이번 요청이 만든 미사용 후보를 정리한다.
        if (!result.candidateClaimed()) {
            cleanupCandidate(candidate);
        }
        return result.response();
    }

    /**
     * 검증된 새 버전 파일을 임의 객체 키로 파일 저장소에 기록한다.
     */
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

    /**
     * Facade에서 준비한 값들을 DB 트랜잭션에 전달할 명령 객체로 조립한다.
     */
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

    /**
     * DB에서 점유하지 않은 저장소 후보를 최선 노력 방식으로 삭제한다.
     *
     * <p>정리 실패는 로그로 남기되 성공한 DB 결과나 원래 예외를 바꾸지 않는다.
     */
    private void cleanupCandidate(StoredFile candidate) {
        try {
            fileStorageService.delete(candidate);
        } catch (RuntimeException cleanupException) {
            log.error("사용되지 않은 저장소 Object 정리에 실패했습니다.", cleanupException);
        }
    }
}
