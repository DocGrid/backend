package com.opensource.docgrid.domain.document.service.command;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class DocumentVersionUploadService {

    private static final int DEFAULT_JOB_PRIORITY = 0;
    private static final int MAX_RETRY_COUNT = 3;
    private static final String IN_PROGRESS_CONSTRAINT = "uk_document_versions_one_in_progress";
    private static final Set<DocumentVersionStatus> IN_PROGRESS_STATUSES = EnumSet.of(
        DocumentVersionStatus.UPLOADED,
        DocumentVersionStatus.PARSING,
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING
    );

    private final UserRepository userRepository;
    private final FileObjectResolutionService fileObjectResolutionService;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingModelQueryService embeddingModelQueryService;
    private final SyncEventWriter syncEventWriter;

    @Transactional(readOnly = true)
    public Optional<Long> prepare(Long userId, Long documentId, ValidatedFile file, String fileHash) {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));
        validate(document, userId, file, fileHash);
        return fileObjectResolutionService.findReusableFileObjectId(fileHash, file.fileSize());
    }

    public DocumentVersionUploadTransactionResult upload(DocumentVersionUploadCommand command) {
        userRepository.findById(command.userId())
            .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));
        Document document = documentRepository.findByIdForUpdate(command.documentId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));
        validate(document, command.userId(), command.validatedFile(), command.fileHash());

        FileObjectResolutionService.Resolution resolution = fileObjectResolutionService.resolve(
            command.userId(), command.validatedFile(), command.fileHash(),
            command.existingFileObjectId(), command.storedFile()
        );
        User user = userRepository.getReferenceById(command.userId());
        int versionNo = documentVersionRepository.findMaxVersionNo(document.getId()) + 1;

        DocumentVersion version;
        try {
            version = documentVersionRepository.saveAndFlush(
                DocumentVersion.builder()
                    .document(document)
                    .fileObject(resolution.fileObject())
                    .versionNo(versionNo)
                    .titleSnapshot(document.getTitle())
                    .fileHash(command.fileHash())
                    .originalFilename(command.validatedFile().originalFilename())
                    .contentType(command.validatedFile().contentType())
                    .status(DocumentVersionStatus.UPLOADED)
                    .createdBy(user)
                    .build()
            );
        } catch (DataIntegrityViolationException exception) {
            if (containsConstraint(exception, IN_PROGRESS_CONSTRAINT)) {
                throw new DocGridException(ErrorCode.DOCUMENT_VERSION_IN_PROGRESS, exception);
            }
            throw exception;
        }

        if (document.getStatus() == DocumentStatus.FAILED) {
            document.markUploaded();
        }

        EmbeddingModel embeddingModel = embeddingModelQueryService.getActiveModel();
        // 새 Version과 인덱싱 의도를 같은 Transaction에 저장해 후속 복구의 기준 Event를 남긴다.
        SyncOutboxEvent sourceEvent = syncEventWriter.recordDocumentVersionCreated(version, embeddingModel);
        EmbeddingJob job = embeddingJobRepository.save(
            EmbeddingJob.builder()
                .documentVersion(version)
                .embeddingModel(embeddingModel)
                .sourceEventId(sourceEvent.getEventId())
                .status(EmbeddingJobStatus.PENDING)
                .priority(DEFAULT_JOB_PRIORITY)
                .maxRetryCount(MAX_RETRY_COUNT)
                .build()
        );

        DocumentVersionUploadResponse response = new DocumentVersionUploadResponse(
            document.getId(),
            version.getId(),
            versionNo,
            job.getId(),
            document.getCurrentVersion() == null ? null : document.getCurrentVersion().getId(),
            document.getStatus(),
            version.getStatus(),
            job.getStatus()
        );
        return new DocumentVersionUploadTransactionResult(response, resolution.candidateClaimed());
    }

    private void validate(Document document, Long userId, ValidatedFile file, String fileHash) {
        if (!document.getOwner().getId().equals(userId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }
        // 삭제된 문서는 다른 조회·수정 경로와 같이 존재하지 않는 것으로 다룬다.
        // 상태 분기까지 내려가면 이 경우만 409가 되어 나머지 API의 404와 어긋난다.
        if (document.getStatus() == DocumentStatus.DELETED) {
            throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        if (document.getSourceType() != DocumentSourceType.UPLOAD) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_NOT_ALLOWED);
        }
        if (document.getDocumentType() != file.documentType()) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_TYPE_MISMATCH);
        }
        if (documentVersionRepository.existsByDocumentIdAndStatusIn(document.getId(), IN_PROGRESS_STATUSES)) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_IN_PROGRESS);
        }

        DocumentVersion comparisonVersion;
        if (document.getStatus() == DocumentStatus.INDEXED) {
            comparisonVersion = document.getCurrentVersion();
            if (comparisonVersion == null || comparisonVersion.getStatus() != DocumentVersionStatus.INDEXED) {
                throw new DocGridException(ErrorCode.DOCUMENT_VERSION_NOT_ALLOWED);
            }
        } else if (document.getStatus() == DocumentStatus.FAILED) {
            comparisonVersion = documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(document.getId())
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_VERSION_NOT_ALLOWED));
            if (comparisonVersion.getStatus() != DocumentVersionStatus.FAILED) {
                throw new DocGridException(ErrorCode.DOCUMENT_VERSION_NOT_ALLOWED);
            }
        } else {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_NOT_ALLOWED);
        }

        if (isSameFile(comparisonVersion, fileHash, file.fileSize())) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_SAME_CONTENT);
        }
    }

    private boolean isSameFile(DocumentVersion version, String fileHash, long fileSize) {
        return fileHash.equals(version.getFileHash())
            && version.getFileObject() != null
            && version.getFileObject().getFileSize() == fileSize;
    }

    private boolean containsConstraint(Throwable throwable, String constraint) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(constraint)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
