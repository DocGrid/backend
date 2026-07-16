package com.opensource.docgrid.domain.document.service.command;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class DocumentUploadService {

    private static final int INITIAL_VERSION = 1;
    private static final int DEFAULT_JOB_PRIORITY = 0;
    private static final int MAX_RETRY_COUNT = 3;

    private final UserRepository userRepository;
    private final FileObjectResolutionService fileObjectResolutionService;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingModelQueryService embeddingModelQueryService;

    @Transactional(readOnly = true)
    public Optional<Long> findReusableFileObjectId(String fileHash, long fileSize) {
        return fileObjectResolutionService.findReusableFileObjectId(fileHash, fileSize);
    }

    public DocumentUploadTransactionResult upload(DocumentUploadCommand command) {
        userRepository.findById(command.userId())
            .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));

        FileObjectResolutionService.Resolution resolution = fileObjectResolutionService.resolve(
            command.userId(), command.validatedFile(), command.fileHash(),
            command.existingFileObjectId(), command.storedFile()
        );
        User user = userRepository.getReferenceById(command.userId());

        Document document = documentRepository.save(
            Document.builder()
                .owner(user)
                .title(command.title())
                .description(command.description())
                .documentType(command.validatedFile().documentType())
                .sourceType(DocumentSourceType.UPLOAD)
                .status(DocumentStatus.UPLOADED)
                .visibility(command.visibility())
                .build()
        );

        DocumentVersion documentVersion = documentVersionRepository.save(
            DocumentVersion.builder()
                .document(document)
                .fileObject(resolution.fileObject())
                .versionNo(INITIAL_VERSION)
                .titleSnapshot(command.title())
                .fileHash(command.fileHash())
                .originalFilename(command.validatedFile().originalFilename())
                .contentType(command.validatedFile().contentType())
                .status(DocumentVersionStatus.UPLOADED)
                .createdBy(user)
                .build()
        );
        document.updateCurrentVersion(documentVersion);

        EmbeddingModel embeddingModel = embeddingModelQueryService.getActiveModel();
        EmbeddingJob embeddingJob = embeddingJobRepository.save(
            EmbeddingJob.builder()
                .documentVersion(documentVersion)
                .embeddingModel(embeddingModel)
                .status(EmbeddingJobStatus.PENDING)
                .priority(DEFAULT_JOB_PRIORITY)
                .maxRetryCount(MAX_RETRY_COUNT)
                .build()
        );

        DocumentUploadResponse response = new DocumentUploadResponse(
            document.getId(),
            documentVersion.getId(),
            resolution.fileObject().getId(),
            embeddingJob.getId(),
            document.getStatus(),
            embeddingJob.getStatus()
        );
        return new DocumentUploadTransactionResult(response, resolution.candidateClaimed());
    }

}
