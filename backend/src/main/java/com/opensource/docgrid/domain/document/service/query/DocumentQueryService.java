package com.opensource.docgrid.domain.document.service.query;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.converter.DocumentDetailConverter;
import com.opensource.docgrid.domain.document.converter.DocumentStatusConverter;
import com.opensource.docgrid.domain.document.converter.DocumentSummaryConverter;
import com.opensource.docgrid.domain.document.dto.response.DocumentContentResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentDetailResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentSummaryResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentStatusProjection;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentQueryService {

    private static final Set<DocumentVersionStatus> PROCESSING_VERSION_STATUSES = EnumSet.of(
        DocumentVersionStatus.UPLOADED,
        DocumentVersionStatus.PARSING,
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING
    );
    private static final Set<EmbeddingJobStatus> ACTIVE_JOB_STATUSES = EnumSet.of(
        EmbeddingJobStatus.PENDING,
        EmbeddingJobStatus.PROCESSING
    );
    private static final Set<DocumentVersionStatus> CONTENT_EXPECTED_VERSION_STATUSES = EnumSet.of(
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING,
        DocumentVersionStatus.INDEXED
    );
    // 목록에는 삭제된 문서만 빼고 모두 노출한다. 인덱싱 중·실패한 문서도 진행 상황 확인 대상이다.
    private static final List<String> LISTABLE_STATUSES = EnumSet.complementOf(
        EnumSet.of(DocumentStatus.DELETED)
    ).stream().map(DocumentStatus::name).toList();
    private static final Sort DOCUMENT_SORT = Sort.by(
        Sort.Order.desc("createdAt"),
        Sort.Order.desc("id")
    );

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final PermissionQueryService permissionQueryService;
    private final DocumentDetailConverter documentDetailConverter;
    private final DocumentStatusConverter documentStatusConverter;
    private final DocumentSummaryConverter documentSummaryConverter;

    public PageResponse<DocumentSummaryResponse> getMyDocuments(
        Long userId,
        DocumentStatus status,
        int page,
        int size
    ) {
        // 1. 검색과 같은 권한 pre-filter로 읽을 수 있는 문서 ID를 먼저 좁힌다.
        List<String> statuses = status == null ? LISTABLE_STATUSES : List.of(status.name());
        List<Long> readableDocumentIds = documentRepository.findReadableDocumentIds(userId, statuses);
        Pageable pageable = PageRequest.of(page, size, DOCUMENT_SORT);
        if (readableDocumentIds.isEmpty()) {
            return PageResponse.from(Page.empty(pageable), List.of());
        }

        // 2. Entity가 Transaction 밖으로 나가기 전에 DTO로 변환한다.
        Page<Document> documents = documentRepository.findAllByIdIn(readableDocumentIds, pageable);
        List<DocumentSummaryResponse> content = documents.getContent().stream()
            .map(documentSummaryConverter::toResponse)
            .toList();
        return PageResponse.from(documents, content);
    }

    /**
     * 읽기 가능한 문서의 Metadata와 현재 버전 요약을 반환한다.
     */
    public DocumentDetailResponse getDocumentDetail(Long userId, Long documentId) {
        // 1. 권한과 삭제 상태를 검증한 상세 조회용 Entity를 가져온다.
        Document document = getReadableDocument(userId, documentId);
        DocumentVersion currentVersion = document.getCurrentVersion();

        // 2. 현재 버전에 저장된 Chunk Set이 있는지 별도 응답 상태로 계산한다.
        boolean contentAvailable = currentVersion != null
            && documentChunkRepository.existsByDocumentVersionId(currentVersion.getId());
        return documentDetailConverter.toResponse(document, contentAvailable);
    }

    /**
     * 현재 버전의 Chunk를 검증하고 중복을 제거해 정규화 Text 전체를 복원한다.
     */
    public DocumentContentResponse getDocumentContent(Long userId, Long documentId) {
        // 1. 읽기 가능한 문서와 현재 버전을 확정한다.
        Document document = getReadableDocument(userId, documentId);
        DocumentVersion currentVersion = document.getCurrentVersion();
        if (currentVersion == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_NOT_AVAILABLE);
        }

        // 2. 저장 순서가 아니라 불변 Chunk Index 순서로 전체 Chunk Set을 조회한다.
        List<DocumentChunk> chunks = documentChunkRepository
            .findAllByDocumentVersionIdOrderByChunkIndexAsc(currentVersion.getId());
        if (chunks.isEmpty()) {
            if (CONTENT_EXPECTED_VERSION_STATUSES.contains(currentVersion.getStatus())) {
                throw inconsistentChunks(currentVersion.getId(), "본문이 있어야 하는 상태지만 Chunk가 없습니다.");
            }
            throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_NOT_AVAILABLE);
        }

        // 3. 전역 Code Point Offset으로 중첩 구간과 Segment 사이 줄바꿈을 복원한다.
        String content = restoreContent(currentVersion.getId(), chunks);
        return new DocumentContentResponse(
            document.getId(),
            currentVersion.getId(),
            currentVersion.getVersionNo(),
            content,
            chunks.size()
        );
    }

    /**
     * 원본 파일 I/O를 Transaction 밖에서 수행할 수 있도록 현재 버전의 저장 위치만 Snapshot으로 반환한다.
     */
    public DocumentFileSnapshot getDocumentFileSnapshot(Long userId, Long documentId) {
        // 1. 동일한 읽기 권한과 삭제 문서 차단 규칙을 원본 파일에도 적용한다.
        Document document = getReadableDocument(userId, documentId);
        DocumentVersion currentVersion = document.getCurrentVersion();
        FileObject fileObject = currentVersion != null ? currentVersion.getFileObject() : null;
        if (currentVersion == null || fileObject == null) {
            log.error("문서 현재 버전의 원본 파일 참조가 없습니다. documentId={}", documentId);
            throw new DocGridException(ErrorCode.DOCUMENT_FILE_REFERENCE_MISSING);
        }

        // 2. JPA Entity 대신 외부 저장소 조회에 필요한 불변 값만 Transaction 밖으로 전달한다.
        return new DocumentFileSnapshot(
            new StoredFile(fileObject.getBucketName(), fileObject.getObjectKey()),
            currentVersion.getOriginalFilename() != null
                ? currentVersion.getOriginalFilename()
                : fileObject.getOriginalFilename(),
            currentVersion.getContentType() != null
                ? currentVersion.getContentType()
                : fileObject.getContentType(),
            fileObject.getFileSize()
        );
    }

    public DocumentStatusResponse getDocumentStatus(Long userId, Long documentId) {
        if (!permissionQueryService.canReadDocument(userId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        List<DocumentStatusProjection> rows = documentRepository.findDocumentStatus(
            documentId,
            PROCESSING_VERSION_STATUSES,
            ACTIVE_JOB_STATUSES
        );
        if (rows.isEmpty()) {
            throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        if (rows.size() != 1 || isInconsistent(rows.get(0))) {
            log.error("문서 인덱싱 상태가 일관되지 않습니다. documentId={}, rowCount={}", documentId, rows.size());
            throw new DocGridException(ErrorCode.INDEXING_STATUS_INCONSISTENT);
        }

        DocumentStatusProjection projection = rows.get(0);
        if (projection.getDocumentStatus() == DocumentStatus.DELETED) {
            throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return documentStatusConverter.toResponse(projection);
    }

    private Document getReadableDocument(Long userId, Long documentId) {
        if (!permissionQueryService.canReadDocument(userId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        Document document = documentRepository.findByIdWithDetail(documentId)
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (document.getStatus() == DocumentStatus.DELETED) {
            throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    private String restoreContent(Long documentVersionId, List<DocumentChunk> chunks) {
        StringBuilder restored = new StringBuilder();
        int previousEnd = 0;

        for (int expectedIndex = 0; expectedIndex < chunks.size(); expectedIndex++) {
            DocumentChunk chunk = chunks.get(expectedIndex);
            int start = chunk.getCharStart();
            int end = chunk.getCharEnd();
            int[] codePoints = chunk.getChunkText().codePoints().toArray();

            // 1. Index와 반열린 Code Point 범위가 Chunk Text와 일치해야 안전하게 중복을 제거할 수 있다.
            if (chunk.getChunkIndex() != expectedIndex
                || start < 0
                || end <= start
                || end - start != codePoints.length
                || (expectedIndex == 0 && start != 0)) {
                throw inconsistentChunks(documentVersionId, "Chunk Index 또는 Offset이 올바르지 않습니다.");
            }

            // 2. Segment 사이는 의도된 LF 한 칸만 허용하고 그보다 큰 유실 구간은 데이터 불일치로 차단한다.
            if (start > previousEnd + 1 || end <= previousEnd) {
                throw inconsistentChunks(documentVersionId, "Chunk 사이에 복원할 수 없는 범위가 있습니다.");
            }
            if (start == previousEnd + 1) {
                restored.append('\n');
            }

            // 3. 이전 Chunk가 이미 포함한 Code Point만 건너뛰고 새 구간을 이어 붙인다.
            int overlap = Math.max(0, previousEnd - start);
            restored.append(new String(codePoints, overlap, codePoints.length - overlap));
            previousEnd = end;
        }
        return restored.toString();
    }

    private DocGridException inconsistentChunks(Long documentVersionId, String reason) {
        log.error("문서 본문 Chunk 데이터가 일관되지 않습니다. documentVersionId={}, reason={}",
            documentVersionId, reason);
        return new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
    }

    private boolean isInconsistent(DocumentStatusProjection projection) {
        boolean hasCurrentVersionNo = projection.getCurrentVersionNo() != null;
        boolean hasCurrentVersionStatus = projection.getCurrentVersionStatus() != null;
        if (hasCurrentVersionNo != hasCurrentVersionStatus) {
            return true;
        }

        boolean hasProcessingVersionNo = projection.getProcessingVersionNo() != null;
        boolean hasProcessingVersionStatus = projection.getProcessingVersionStatus() != null;
        boolean hasProcessingJobStatus = projection.getProcessingJobStatus() != null;
        return hasProcessingVersionNo != hasProcessingVersionStatus
            || hasProcessingVersionNo != hasProcessingJobStatus;
    }
}
