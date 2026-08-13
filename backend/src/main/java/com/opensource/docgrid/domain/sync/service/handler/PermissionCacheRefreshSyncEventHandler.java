package com.opensource.docgrid.domain.sync.service.handler;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.entity.DocumentPermission;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.permission.repository.DocumentPermissionRepository;
import com.opensource.docgrid.domain.permission.service.command.UserDocumentAccessCacheService;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.enums.SyncPermissionOperation;
import com.opensource.docgrid.domain.sync.service.SyncEventHandler;
import com.opensource.docgrid.domain.sync.service.SyncEventPayloadReader;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 직접 USER 문서·컬렉션 권한 원장을 접근 캐시에 다시 투영한다.
 *
 * <p>권한이 이미 회수됐거나 ROLE·DEPARTMENT 대상이면 해당 출처 캐시를 무효화한다. 캐시는 최종 권한
 * 원장이 아니므로 이 Handler는 Vector를 변경하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PermissionCacheRefreshSyncEventHandler implements SyncEventHandler {

    private final DocumentPermissionRepository documentPermissionRepository;
    private final CollectionPermissionRepository collectionPermissionRepository;
    private final CollectionDocumentRepository collectionDocumentRepository;
    private final UserDocumentAccessCacheService cacheService;
    private final SyncEventPayloadReader payloadReader;

    @Override
    public Set<SyncEventType> supportedTypes() {
        return Set.of(SyncEventType.PERMISSION_CACHE_REFRESH_REQUESTED);
    }

    @Override
    public void handle(SyncOutboxEvent event) {
        AccessSourceType sourceType = parseSourceType(event);
        SyncPermissionOperation operation = parseOperation(event);
        if (event.getAggregateId() == null || sourceType == AccessSourceType.OWNER) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
        if (operation == SyncPermissionOperation.REVOKED) {
            cacheService.bulkRevokeBySource(sourceType, event.getAggregateId());
            return;
        }

        switch (sourceType) {
            case DIRECT_DOCUMENT_PERMISSION -> refreshDocumentPermission(event.getAggregateId());
            case DIRECT_COLLECTION_PERMISSION -> refreshCollectionPermission(event.getAggregateId());
            case OWNER -> throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
    }

    private void refreshDocumentPermission(Long permissionId) {
        Optional<DocumentPermission> permission = documentPermissionRepository.findById(permissionId);
        if (permission.isEmpty() || permission.get().getTargetType() != PermissionTargetType.USER) {
            cacheService.bulkRevokeBySource(AccessSourceType.DIRECT_DOCUMENT_PERMISSION, permissionId);
            return;
        }
        DocumentPermission current = permission.get();
        cacheService.grantUserPermission(
            current.getUser(),
            current.getDocument(),
            current.isCanRead(),
            current.isCanWrite(),
            current.isCanAdmin(),
            AccessSourceType.DIRECT_DOCUMENT_PERMISSION,
            permissionId,
            current.getExpiresAt()
        );
    }

    private void refreshCollectionPermission(Long permissionId) {
        Optional<CollectionPermission> permission = collectionPermissionRepository.findById(permissionId);
        if (permission.isEmpty() || permission.get().getTargetType() != PermissionTargetType.USER) {
            cacheService.bulkRevokeBySource(AccessSourceType.DIRECT_COLLECTION_PERMISSION, permissionId);
            return;
        }
        CollectionPermission current = permission.get();
        List<Document> documents = collectionDocumentRepository
            .findAllByCollectionId(current.getCollection().getId())
            .stream()
            .map(CollectionDocument::getDocument)
            .toList();
        cacheService.bulkGrantUserPermission(
            current.getUser(),
            documents,
            current.isCanRead(),
            current.isCanWrite(),
            current.isCanAdmin(),
            AccessSourceType.DIRECT_COLLECTION_PERMISSION,
            permissionId,
            current.getExpiresAt()
        );
    }

    private AccessSourceType parseSourceType(SyncOutboxEvent event) {
        try {
            return AccessSourceType.valueOf(payloadReader.requiredText(event, "sourceType"));
        } catch (IllegalArgumentException exception) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT, exception);
        }
    }

    private SyncPermissionOperation parseOperation(SyncOutboxEvent event) {
        try {
            return SyncPermissionOperation.valueOf(payloadReader.requiredText(event, "operation"));
        } catch (IllegalArgumentException exception) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT, exception);
        }
    }
}
