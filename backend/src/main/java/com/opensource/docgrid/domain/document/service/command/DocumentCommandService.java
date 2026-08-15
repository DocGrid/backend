package com.opensource.docgrid.domain.document.service.command;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.dto.request.UpdateDocumentMetadataRequest;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 논리 문서의 Metadata 변경과 soft delete를 하나의 쓰기 트랜잭션 경계에서 처리한다.
 *
 * <p>원본 파일과 버전 이력의 생명주기는 다루지 않으며, 삭제 후 검색 정리는 Outbox Event에 위임한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentCommandService {

    private final DocumentRepository documentRepository;
    private final PermissionQueryService permissionQueryService;
    private final SyncEventWriter syncEventWriter;
    private final Clock clock;

    /**
     * 문서 쓰기 권한이 있는 사용자가 현재 표시 제목과 설명을 변경한다.
     */
    public void updateMetadata(Long userId, Long documentId, UpdateDocumentMetadataRequest request) {
        // 1. 삭제와 수정 경쟁을 문서 행에서 직렬화하고 이미 삭제된 문서는 존재하지 않는 것으로 처리한다.
        Document document = findActiveDocumentForUpdate(documentId);

        // 2. 소유자와 직접·상속 WRITE 권한을 기존 공통 권한 규칙으로 검증한다.
        if (!permissionQueryService.canWriteDocument(userId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 3. 현재 Metadata만 갱신하고 과거 Version의 제목 Snapshot은 보존한다.
        document.updateMetadata(request.title(), request.description());
    }

    /**
     * 문서 관리 권한이 있는 사용자가 문서를 soft delete하고 검색 정리 의도를 기록한다.
     */
    public void deleteDocument(Long userId, Long documentId) {
        // 1. 중복 삭제와 다른 문서 변경을 직렬화하고 삭제된 문서는 다시 노출하지 않는다.
        Document document = findActiveDocumentForUpdate(documentId);

        // 2. 소유자와 직접·상속 ADMIN 권한만 문서 생명주기를 종료할 수 있다.
        if (!permissionQueryService.canAdminDocument(userId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 3. 원본 파일과 Version 이력은 보존하면서 문서 원장을 즉시 검색·조회 대상에서 제외한다.
        document.markDeleted(LocalDateTime.now(clock));

        // 4. ACTIVE Vector 비활성화를 같은 Transaction의 멱등 Outbox Event로 남긴다.
        syncEventWriter.recordDocumentDeleted(document);
    }

    private Document findActiveDocumentForUpdate(Long documentId) {
        return documentRepository.findByIdForUpdate(documentId)
            .filter(document -> document.getStatus() != DocumentStatus.DELETED)
            .filter(document -> document.getDeletedAt() == null)
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));
    }
}
