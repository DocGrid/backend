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

    /**
     * 이 Handler가 처리할 권한 캐시 재투영 Event 종류를 반환한다.
     */
    @Override
    public Set<SyncEventType> supportedTypes() {
        return Set.of(SyncEventType.PERMISSION_CACHE_REFRESH_REQUESTED);
    }

    /**
     * 권한 원장 출처와 변경 작업을 해석해 사용자 접근 캐시를 부여 또는 회수 상태로 맞춘다.
     */
    @Override
    public void handle(SyncOutboxEvent event) {
        // 1. Payload에서 제한된 권한 출처와 작업 Enum을 파싱한다.
        AccessSourceType sourceType = parseSourceType(event);
        SyncPermissionOperation operation = parseOperation(event);

        // 2. 캐시 원장으로 사용할 수 없는 Aggregate와 OWNER 출처 Event를 거부한다.
        if (event.getAggregateId() == null || sourceType == AccessSourceType.OWNER) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }

        // 3. 회수 Event는 원장 존재 여부와 무관하게 해당 출처에서 파생된 캐시를 모두 제거한다.
        if (operation == SyncPermissionOperation.REVOKED) {
            cacheService.bulkRevokeBySource(sourceType, event.getAggregateId());
            return;
        }

        // 4. 부여 Event는 직접 문서 또는 컬렉션 권한 원장을 다시 읽어 현재 값으로 캐시를 재생성한다.
        switch (sourceType) {
            case DIRECT_DOCUMENT_PERMISSION -> refreshDocumentPermission(event.getAggregateId());
            case DIRECT_COLLECTION_PERMISSION -> refreshCollectionPermission(event.getAggregateId());
            case OWNER -> throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
    }

    /**
     * 직접 문서 권한이 현재 USER 대상이면 한 문서의 캐시를 갱신하고, 아니면 기존 출처 캐시를 제거한다.
     */
    private void refreshDocumentPermission(Long permissionId) {
        // 1. Event 이후 원장이 삭제됐거나 USER 대상이 아니게 됐으면 과거 캐시를 제거한다.
        Optional<DocumentPermission> permission = documentPermissionRepository.findById(permissionId);
        if (permission.isEmpty() || permission.get().getTargetType() != PermissionTargetType.USER) {
            cacheService.bulkRevokeBySource(AccessSourceType.DIRECT_DOCUMENT_PERMISSION, permissionId);
            return;
        }

        // 2. 현재 권한 비트와 만료 시각을 그대로 단일 문서 접근 캐시에 투영한다.
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

    /**
     * 직접 컬렉션 권한이 현재 USER 대상이면 소속 문서 전체에 캐시를 투영하고, 아니면 기존 캐시를 제거한다.
     */
    private void refreshCollectionPermission(Long permissionId) {
        // 1. 원장이 사라졌거나 USER 대상이 아니면 이 권한에서 파생된 모든 문서 캐시를 회수한다.
        Optional<CollectionPermission> permission = collectionPermissionRepository.findById(permissionId);
        if (permission.isEmpty() || permission.get().getTargetType() != PermissionTargetType.USER) {
            cacheService.bulkRevokeBySource(AccessSourceType.DIRECT_COLLECTION_PERMISSION, permissionId);
            return;
        }

        // 2. 현재 컬렉션 구성 문서를 조회해 동일 권한 비트와 만료 시각을 일괄 투영한다.
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

    /**
     * Event Payload의 sourceType 문자열을 지원하는 접근 출처 Enum으로 변환한다.
     */
    private AccessSourceType parseSourceType(SyncOutboxEvent event) {
        try {
            return AccessSourceType.valueOf(payloadReader.requiredText(event, "sourceType"));
        } catch (IllegalArgumentException exception) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT, exception);
        }
    }

    /**
     * Event Payload의 operation 문자열을 권한 부여·회수 Enum으로 변환한다.
     */
    private SyncPermissionOperation parseOperation(SyncOutboxEvent event) {
        try {
            return SyncPermissionOperation.valueOf(payloadReader.requiredText(event, "operation"));
        } catch (IllegalArgumentException exception) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT, exception);
        }
    }
}
