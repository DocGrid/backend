package com.opensource.docgrid.domain.collection.service.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.collection.converter.CollectionConverter;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class CollectionQueryService {

    private final CollectionRepository collectionRepository;
    private final CollectionConverter collectionConverter;

    // 컬렉션 단건 조회
    public CollectionResponse getCollection(Long collectionId) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        return collectionConverter.toResponse(collection);
    }

    // 내 컬렉션 목록 조회 (ACTIVE 상태만)
    public List<CollectionResponse> getMyCollections(Long userId) {
        return collectionRepository.findAllByOwnerIdAndStatus(userId, CollectionStatus.ACTIVE)
                .stream()
                .map(collectionConverter::toResponse)
                .toList();
    }
}
