package com.opensource.docgrid.domain.collection.service.command;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.collection.converter.CollectionConverter;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.collection.dto.request.AddDocumentRequest;
import com.opensource.docgrid.domain.collection.dto.request.CreateCollectionRequest;
import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.permission.service.command.UserDocumentAccessCacheService;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class CollectionCommandService {

    private final CollectionRepository collectionRepository;
    private final CollectionDocumentRepository collectionDocumentRepository;
    private final CollectionPermissionRepository collectionPermissionRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final CollectionConverter collectionConverter;
    private final PermissionQueryService permissionQueryService;
    private final UserDocumentAccessCacheService cacheService;

    // 폴더 생성
    public CollectionResponse createCollection(Long userId, CreateCollectionRequest request) {
        User owner = userRepository.getReferenceById(userId);

        DocumentCollection parentCollection = null; // 상위 폴더 지정은 선택 사항이라 null로 초기화
        if (request.parentCollectionId() != null) {
            parentCollection = collectionRepository.findById(request.parentCollectionId())
                    .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        }

        VisibilityType visibility = request.visibility() != null ? request.visibility() : VisibilityType.PRIVATE;

        DocumentCollection collection = DocumentCollection.builder()
                .owner(owner)
                .parentCollection(parentCollection)
                .name(request.name())
                .description(request.description())
                .visibility(visibility)
                .status(CollectionStatus.ACTIVE)
                .build();

        collectionRepository.save(collection);
        return collectionConverter.toResponse(collection);
    }

    // 폴더에 문서 추가
    public CollectionDocumentResponse addDocument(Long collectionId, Long userId, AddDocumentRequest request) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .filter(c -> c.getStatus() != CollectionStatus.DELETED)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

        if (!permissionQueryService.canWriteCollection(userId, collection)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        Document document = documentRepository.findById(request.documentId())
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        // 이미 컬렉션에 문서가 존재하는지 확인
        if (collectionDocumentRepository.existsByCollectionIdAndDocumentId(collectionId, request.documentId())) {
            throw new DocGridException(ErrorCode.COLLECTION_DOCUMENT_ALREADY_EXISTS);
        }

        User addedBy = userRepository.getReferenceById(userId); // 문서를 추가한 사용자 정보 가져오기

        CollectionDocument collectionDocument = CollectionDocument.builder()
                .collection(collection)
                .document(document)
                .addedBy(addedBy)
                .addedAt(LocalDateTime.now())
                .build();

        collectionDocumentRepository.save(collectionDocument);
        return collectionConverter.toDocumentResponse(collectionDocument);
    }

    // 컬렉션 soft delete — 소유자만 가능
    public void deleteCollection(Long collectionId, Long userId) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .filter(c -> c.getStatus() != CollectionStatus.DELETED)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

        if (!collection.getOwner().getId().equals(userId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 폴더에 속한 모든 권한 삭제 및 캐시 무효화
        List<CollectionPermission> permissions = collectionPermissionRepository.findAllByCollectionId(collectionId);
        permissions.stream()
                .filter(p -> p.getTargetType() == PermissionTargetType.USER)
            // 컬렉션 권한이 USER 대상인 경우에만 캐시 무효화
                .forEach(p -> cacheService.bulkRevokeBySource(AccessSourceType.DIRECT_COLLECTION_PERMISSION, p.getId()));
        collectionPermissionRepository.deleteAll(permissions); // 컬렉션 권한 삭제

        collection.markDeleted(LocalDateTime.now()); // 폴더 상태를 DELETED로 변경
    }

    // 컬렉션에서 문서 제거 — 소유자만 가능
    public void removeDocument(Long collectionId, Long documentId, Long userId) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .filter(c -> c.getStatus() != CollectionStatus.DELETED)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

        if (!collection.getOwner().getId().equals(userId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        CollectionDocument collectionDocument = collectionDocumentRepository
                .findByCollectionIdAndDocumentId(collectionId, documentId)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_DOCUMENT_NOT_FOUND));

        List<Long> userPermissionIds = collectionPermissionRepository.findAllByCollectionId(collectionId)
                .stream()
                .filter(p -> p.getTargetType() == PermissionTargetType.USER)
                .map(CollectionPermission::getId)
                .toList();

        cacheService.bulkRevokeBySourcesForDocument(
                AccessSourceType.DIRECT_COLLECTION_PERMISSION, userPermissionIds, documentId);

        collectionDocumentRepository.delete(collectionDocument);
    }
}
