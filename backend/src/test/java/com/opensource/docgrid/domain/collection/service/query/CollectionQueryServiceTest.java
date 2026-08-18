package com.opensource.docgrid.domain.collection.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.opensource.docgrid.domain.collection.converter.CollectionConverter;
import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentListItemResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.collection.fixture.CollectionFixture;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 컬렉션 단건과 읽기 가능한 컬렉션 문서 페이지의 권한·Pagination 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CollectionQueryService 단위 테스트")
class CollectionQueryServiceTest {

    @InjectMocks
    private CollectionQueryService collectionQueryService;

    @Mock
    private CollectionRepository collectionRepository;

    @Mock
    private CollectionDocumentRepository collectionDocumentRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private CollectionConverter collectionConverter;

    @Mock
    private PermissionQueryService permissionQueryService;

    @Test
    @DisplayName("읽기 권한이 있는 사용자가 조회하면 CollectionResponse를 반환한다")
    void getCollection_returnsResponse_when_collectionExists() {
        DocumentCollection collection = CollectionFixture.createCollection();
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(collectionConverter.toResponse(collection)).willReturn(expected);

        CollectionResponse result = collectionQueryService.getCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);

        assertThat(result).isEqualTo(expected);
        then(collectionConverter).should().toResponse(collection);
    }

    @Test
    @DisplayName("존재하지 않는 컬렉션 ID로 조회하면 COLLECTION_NOT_FOUND 예외가 발생한다")
    void getCollection_throws_when_collectionNotFound() {
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collectionQueryService.getCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제된 컬렉션을 조회하면 COLLECTION_NOT_FOUND 예외가 발생한다")
    void getCollection_throws_when_collectionDeleted() {
        DocumentCollection collection = CollectionFixture.createCollection();
        org.springframework.test.util.ReflectionTestUtils.setField(collection, "status", CollectionStatus.DELETED);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        assertThatThrownBy(() -> collectionQueryService.getCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("읽기 권한이 없는 사용자가 조회하면 PERMISSION_DENIED 예외가 발생한다")
    void getCollection_throws_when_noReadPermission() {
        DocumentCollection collection = CollectionFixture.createCollection();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canReadCollection(otherUserId, collection)).willReturn(false);

        assertThatThrownBy(() -> collectionQueryService.getCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("읽을 수 있는 컬렉션이 없으면 빈 페이지를 반환한다")
    void getCollections_returnsEmptyPage_whenNoReadableCollection() {
        given(collectionRepository.findReadableCollectionIds(CollectionFixture.USER_ID, null)).willReturn(List.of());

        PageResponse<CollectionResponse> result = collectionQueryService.getCollections(CollectionFixture.USER_ID, null, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        then(collectionRepository).should(never()).findAllByIdIn(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("읽을 수 있는 컬렉션 ID로 페이지를 조회해서 응답으로 변환한다")
    void getCollections_returnsPagedResponses() {
        DocumentCollection collection = CollectionFixture.createCollection();
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        List<Long> readableIds = List.of(collection.getId());
        given(collectionRepository.findReadableCollectionIds(CollectionFixture.USER_ID, null)).willReturn(readableIds);
        given(collectionRepository.findAllByIdIn(org.mockito.ArgumentMatchers.eq(readableIds), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(collection), PageRequest.of(0, 20), 1));
        given(collectionConverter.toResponse(collection)).willReturn(expected);

        PageResponse<CollectionResponse> result = collectionQueryService.getCollections(CollectionFixture.USER_ID, null, 0, 20);

        assertThat(result.content()).containsExactly(expected);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("keyword를 그대로 repository에 전달한다")
    void getCollections_passesKeywordToRepository() {
        given(collectionRepository.findReadableCollectionIds(CollectionFixture.USER_ID, "개발")).willReturn(List.of());

        collectionQueryService.getCollections(CollectionFixture.USER_ID, "개발", 0, 20);

        then(collectionRepository).should().findReadableCollectionIds(CollectionFixture.USER_ID, "개발");
    }

    @Test
    @DisplayName("부모 읽기 권한이 있으면 자식 컬렉션 목록을 반환한다")
    void getChildren_returnsResponses_when_parentIsReadable() {
        DocumentCollection parent = CollectionFixture.createCollection();
        DocumentCollection child = CollectionFixture.createChildCollection(parent.getOwner(), parent, 2L);
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(parent));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, parent)).willReturn(true);
        given(collectionRepository.findAllByParentCollectionIdAndStatus(CollectionFixture.COLLECTION_ID, CollectionStatus.ACTIVE))
                .willReturn(List.of(child));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, child)).willReturn(true);
        given(collectionConverter.toResponse(child)).willReturn(expected);

        List<CollectionResponse> result = collectionQueryService.getChildren(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);

        assertThat(result).containsExactly(expected);
    }

    @Test
    @DisplayName("부모 읽기 권한이 없으면 PERMISSION_DENIED 예외가 발생한다")
    void getChildren_throws_when_parentReadIsDenied() {
        DocumentCollection parent = CollectionFixture.createCollection();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(parent));
        given(permissionQueryService.canReadCollection(otherUserId, parent)).willReturn(false);

        assertThatThrownBy(() -> collectionQueryService.getChildren(otherUserId, CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
        then(collectionRepository).should(never()).findAllByParentCollectionIdAndStatus(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("부모는 읽을 수 있어도 자식은 개별 읽기 권한이 없으면 목록에서 제외된다")
    void getChildren_excludesChild_when_childReadIsDenied() {
        DocumentCollection parent = CollectionFixture.createCollection();
        DocumentCollection readableChild = CollectionFixture.createChildCollection(parent.getOwner(), parent, 2L);
        DocumentCollection deniedChild = CollectionFixture.createChildCollection(parent.getOwner(), parent, 3L);
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(parent));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, parent)).willReturn(true);
        given(collectionRepository.findAllByParentCollectionIdAndStatus(CollectionFixture.COLLECTION_ID, CollectionStatus.ACTIVE))
                .willReturn(List.of(readableChild, deniedChild));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, readableChild)).willReturn(true);
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, deniedChild)).willReturn(false);
        given(collectionConverter.toResponse(readableChild)).willReturn(expected);

        List<CollectionResponse> result = collectionQueryService.getChildren(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);

        assertThat(result).containsExactly(expected);
    }

    @Test
    @DisplayName("컬렉션 문서 목록은 읽기 가능한 문서만 최신 추가순으로 페이지 반환한다")
    void getCollectionDocuments_returnsOnlyReadableDocuments() {
        DocumentCollection collection = CollectionFixture.createCollection();
        CollectionDocument collectionDocument = org.mockito.Mockito.mock(CollectionDocument.class);
        CollectionDocumentListItemResponse expected = org.mockito.Mockito.mock(
                CollectionDocumentListItemResponse.class
        );
        List<Long> readableDocumentIds = List.of(CollectionFixture.DOCUMENT_ID);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(documentRepository.findReadableDocumentIdsInCollection(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID),
                org.mockito.ArgumentMatchers.eq(CollectionFixture.COLLECTION_ID),
                org.mockito.ArgumentMatchers.anyList()
        )).willReturn(readableDocumentIds);
        given(collectionDocumentRepository.findReadableDocuments(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.COLLECTION_ID),
                org.mockito.ArgumentMatchers.eq(readableDocumentIds),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(collectionDocument), PageRequest.of(0, 20), 1));
        given(collectionConverter.toDocumentListItemResponse(collectionDocument)).willReturn(expected);

        PageResponse<CollectionDocumentListItemResponse> result = collectionQueryService.getCollectionDocuments(
                CollectionFixture.USER_ID,
                CollectionFixture.COLLECTION_ID,
                0,
                20
        );

        assertThat(result.content()).containsExactly(expected);
        assertThat(result.totalElements()).isEqualTo(1);
        then(documentRepository).should().findReadableDocumentIdsInCollection(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID),
                org.mockito.ArgumentMatchers.eq(CollectionFixture.COLLECTION_ID),
                org.mockito.ArgumentMatchers.argThat(statuses -> !statuses.contains("DELETED"))
        );
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        then(collectionDocumentRepository).should().findReadableDocuments(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.COLLECTION_ID),
                org.mockito.ArgumentMatchers.eq(readableDocumentIds),
                pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("addedAt").isDescending()).isTrue();
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    @DisplayName("읽기 가능한 문서가 없으면 숨김 문서 수를 노출하지 않는 빈 페이지를 반환한다")
    void getCollectionDocuments_returnsEmptyPage_whenNoDocumentIsReadable() {
        DocumentCollection collection = CollectionFixture.createCollection();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(documentRepository.findReadableDocumentIdsInCollection(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID),
                org.mockito.ArgumentMatchers.eq(CollectionFixture.COLLECTION_ID),
                org.mockito.ArgumentMatchers.anyList()
        )).willReturn(List.of());

        PageResponse<CollectionDocumentListItemResponse> result = collectionQueryService.getCollectionDocuments(
                CollectionFixture.USER_ID,
                CollectionFixture.COLLECTION_ID,
                0,
                20
        );

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        then(collectionDocumentRepository).should(never()).findReadableDocuments(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        );
    }

    @Test
    @DisplayName("컬렉션 읽기 권한이 없으면 문서 ID 조회 전에 거부한다")
    void getCollectionDocuments_throwsBeforeDocumentQuery_whenCollectionReadIsDenied() {
        DocumentCollection collection = CollectionFixture.createCollection();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canReadCollection(otherUserId, collection)).willReturn(false);

        assertThatThrownBy(() -> collectionQueryService.getCollectionDocuments(
                otherUserId,
                CollectionFixture.COLLECTION_ID,
                0,
                20
        ))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
        then(documentRepository).shouldHaveNoInteractions();
    }
}
