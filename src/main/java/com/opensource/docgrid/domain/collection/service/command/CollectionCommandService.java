package com.opensource.docgrid.domain.collection.service.command;

import java.time.LocalDateTime;

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
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final CollectionConverter collectionConverter;
    private final PermissionQueryService permissionQueryService;

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
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

        if (!permissionQueryService.canWriteCollection(userId, collectionId)) {
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
}
