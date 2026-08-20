package com.opensource.docgrid.domain.collection.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import com.opensource.docgrid.domain.collection.converter.CollectionConverter;
import com.opensource.docgrid.domain.collection.dto.request.AddDocumentRequest;
import com.opensource.docgrid.domain.collection.dto.request.CreateCollectionRequest;
import com.opensource.docgrid.domain.collection.dto.request.UpdateCollectionVisibilityRequest;
import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.fixture.CollectionFixture;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.fixture.PermissionFixture;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.permission.service.command.UserDocumentAccessCacheService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("CollectionCommandService 단위 테스트")
class CollectionCommandServiceTest {

    @InjectMocks
    private CollectionCommandService collectionCommandService;

    @Mock
    private CollectionRepository collectionRepository;

    @Mock
    private CollectionDocumentRepository collectionDocumentRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CollectionConverter collectionConverter;

    @Mock
    private CollectionPermissionRepository collectionPermissionRepository;

    @Mock
    private UserDocumentAccessCacheService cacheService;

    @Mock
    private PermissionQueryService permissionQueryService;

    // ==================== createCollection ====================

    @Test
    @DisplayName("정상 요청으로 컬렉션을 생성하면 CollectionResponse를 반환한다")
    void createCollection_succeeds_when_validRequest() {
        User owner = CollectionFixture.createOwner();
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        CreateCollectionRequest request = new CreateCollectionRequest(
                CollectionFixture.COLLECTION_NAME, CollectionFixture.COLLECTION_DESCRIPTION, null, VisibilityType.PRIVATE
        );
        given(userRepository.getReferenceById(CollectionFixture.USER_ID)).willReturn(owner);
        given(collectionConverter.toResponse(any(DocumentCollection.class), any(String.class))).willReturn(expected);

        CollectionResponse result = collectionCommandService.createCollection(CollectionFixture.USER_ID, request);

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<DocumentCollection> captor = ArgumentCaptor.forClass(DocumentCollection.class);
        then(collectionRepository).should().save(captor.capture());
        DocumentCollection saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo(CollectionFixture.COLLECTION_NAME);
        assertThat(saved.getDescription()).isEqualTo(CollectionFixture.COLLECTION_DESCRIPTION);
        assertThat(saved.getOwner()).isEqualTo(owner);
    }

    @Test
    @DisplayName("visibility가 null이면 PRIVATE으로 기본 설정된다")
    void createCollection_defaults_visibility_to_private_when_null() {
        User owner = CollectionFixture.createOwner();
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        CreateCollectionRequest request = new CreateCollectionRequest(
                CollectionFixture.COLLECTION_NAME, null, null, null
        );
        given(userRepository.getReferenceById(CollectionFixture.USER_ID)).willReturn(owner);
        given(collectionConverter.toResponse(any(DocumentCollection.class), any(String.class))).willReturn(expected);

        CollectionResponse result = collectionCommandService.createCollection(CollectionFixture.USER_ID, request);

        assertThat(result.visibility()).isEqualTo(VisibilityType.PRIVATE);
    }

    @Test
    @DisplayName("존재하는 상위 컬렉션 ID를 지정하고 쓰기 권한이 있으면 parentCollection이 설정된 컬렉션이 생성된다")
    void createCollection_succeeds_with_parentCollection() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection parent = CollectionFixture.createCollection(owner);
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        CreateCollectionRequest request = new CreateCollectionRequest(
                "하위 컬렉션", null, CollectionFixture.COLLECTION_ID, VisibilityType.PRIVATE
        );
        given(userRepository.getReferenceById(CollectionFixture.USER_ID)).willReturn(owner);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(parent));
        given(permissionQueryService.canWriteCollection(CollectionFixture.USER_ID, parent)).willReturn(true);
        given(collectionConverter.toResponse(any(DocumentCollection.class), any(String.class))).willReturn(expected);

        collectionCommandService.createCollection(CollectionFixture.USER_ID, request);

        then(collectionRepository).should().findById(CollectionFixture.COLLECTION_ID);
        ArgumentCaptor<DocumentCollection> captor = ArgumentCaptor.forClass(DocumentCollection.class);
        then(collectionRepository).should().save(captor.capture());
        assertThat(captor.getValue().getParentCollection()).isSameAs(parent);
    }

    @Test
    @DisplayName("존재하지 않는 상위 컬렉션 ID를 지정하면 COLLECTION_NOT_FOUND 예외가 발생한다")
    void createCollection_throws_when_parentNotFound() {
        User owner = CollectionFixture.createOwner();
        CreateCollectionRequest request = new CreateCollectionRequest(
                "하위 컬렉션", null, 999L, VisibilityType.PRIVATE
        );
        given(userRepository.getReferenceById(CollectionFixture.USER_ID)).willReturn(owner);
        given(collectionRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collectionCommandService.createCollection(CollectionFixture.USER_ID, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("상위 컬렉션에 쓰기 권한이 없으면 PERMISSION_DENIED 예외가 발생한다")
    void createCollection_throws_when_noWritePermissionOnParent() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection parent = CollectionFixture.createCollection(owner);
        CreateCollectionRequest request = new CreateCollectionRequest(
                "하위 컬렉션", null, CollectionFixture.COLLECTION_ID, VisibilityType.PRIVATE
        );
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(parent));
        given(permissionQueryService.canWriteCollection(otherUserId, parent)).willReturn(false);

        assertThatThrownBy(() -> collectionCommandService.createCollection(otherUserId, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    // ==================== addDocument ====================

    @Test
    @DisplayName("소유자가 문서를 추가하면 CollectionDocumentResponse를 반환한다")
    void addDocument_succeeds_when_validRequest() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Document document = CollectionFixture.createDocument(owner);
        CollectionDocumentResponse expected = CollectionFixture.createCollectionDocumentResponse();
        AddDocumentRequest request = new AddDocumentRequest(CollectionFixture.DOCUMENT_ID);

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canWriteCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(collectionDocumentRepository.existsByCollectionIdAndDocumentId(
                CollectionFixture.COLLECTION_ID, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(userRepository.getReferenceById(CollectionFixture.USER_ID)).willReturn(owner);
        given(collectionConverter.toDocumentResponse(any())).willReturn(expected);

        CollectionDocumentResponse result = collectionCommandService.addDocument(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, request);

        assertThat(result).isEqualTo(expected);
        then(collectionDocumentRepository).should().save(any());
    }

    @Test
    @DisplayName("존재하지 않는 컬렉션에 문서를 추가하면 COLLECTION_NOT_FOUND 예외가 발생한다")
    void addDocument_throws_when_collectionNotFound() {
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collectionCommandService.addDocument(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, new AddDocumentRequest(CollectionFixture.DOCUMENT_ID)))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("컬렉션 소유자가 아닌 사용자가 문서를 추가하면 PERMISSION_DENIED 예외가 발생한다")
    void addDocument_throws_when_notOwner() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        assertThatThrownBy(() -> collectionCommandService.addDocument(
                CollectionFixture.COLLECTION_ID, otherUserId, new AddDocumentRequest(CollectionFixture.DOCUMENT_ID)))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("존재하지 않는 문서 ID로 추가하면 DOCUMENT_NOT_FOUND 예외가 발생한다")
    void addDocument_throws_when_documentNotFound() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canWriteCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collectionCommandService.addDocument(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, new AddDocumentRequest(CollectionFixture.DOCUMENT_ID)))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("이미 컬렉션에 추가된 문서를 다시 추가하면 COLLECTION_DOCUMENT_ALREADY_EXISTS 예외가 발생한다")
    void addDocument_throws_when_alreadyExists() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Document document = CollectionFixture.createDocument(owner);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canWriteCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(collectionDocumentRepository.existsByCollectionIdAndDocumentId(
                CollectionFixture.COLLECTION_ID, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        assertThatThrownBy(() -> collectionCommandService.addDocument(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, new AddDocumentRequest(CollectionFixture.DOCUMENT_ID)))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_DOCUMENT_ALREADY_EXISTS);
    }

    // ==================== updateVisibility ====================

    @Test
    @DisplayName("소유자면 공개 범위를 PUBLIC으로 변경한다")
    void updateVisibility_succeeds_whenUserIsOwner() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        collectionCommandService.updateVisibility(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID,
                new UpdateCollectionVisibilityRequest(VisibilityType.PUBLIC));

        assertThat(collection.getVisibility()).isEqualTo(VisibilityType.PUBLIC);
    }

    @Test
    @DisplayName("소유자가 아니면 PERMISSION_DENIED 예외가 발생한다")
    void updateVisibility_throws_whenUserIsNotOwner() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        assertThatThrownBy(() -> collectionCommandService.updateVisibility(
                CollectionFixture.COLLECTION_ID, otherUserId,
                new UpdateCollectionVisibilityRequest(VisibilityType.PUBLIC)))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);

        assertThat(collection.getVisibility()).isEqualTo(VisibilityType.PRIVATE);
    }

    @Test
    @DisplayName("COLLECTION/DEPARTMENT로 변경을 요청하면 거부한다")
    void updateVisibility_throws_whenValueNotSupported() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        assertThatThrownBy(() -> collectionCommandService.updateVisibility(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID,
                new UpdateCollectionVisibilityRequest(VisibilityType.DEPARTMENT)))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_VISIBILITY_NOT_SUPPORTED);

        assertThat(collection.getVisibility()).isEqualTo(VisibilityType.PRIVATE);
    }

    @Test
    @DisplayName("존재하지 않는 컬렉션이면 COLLECTION_NOT_FOUND 예외가 발생한다")
    void updateVisibility_throws_whenCollectionNotFound() {
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collectionCommandService.updateVisibility(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID,
                new UpdateCollectionVisibilityRequest(VisibilityType.PUBLIC)))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    // ==================== deleteCollection ====================

    @Test
    @DisplayName("소유자가 컬렉션을 삭제하면 soft delete 처리되고 권한 및 캐시가 무효화된다")
    void deleteCollection_succeeds_when_owner() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        CollectionPermission userPermission = PermissionFixture.createCollectionPermission(collection, owner);
        List<Long> targetIds = List.of(CollectionFixture.COLLECTION_ID);

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(collectionRepository.findDescendantIdsInclusive(CollectionFixture.COLLECTION_ID)).willReturn(targetIds);
        given(collectionPermissionRepository.findAllByCollectionIdIn(targetIds))
                .willReturn(List.of(userPermission));
        given(collectionDocumentRepository.findAllByCollectionIdIn(targetIds)).willReturn(List.of());
        given(collectionRepository.findAllById(targetIds)).willReturn(List.of(collection));

        collectionCommandService.deleteCollection(CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID);

        then(cacheService).should().bulkRevokeBySource(any(), any());
        then(collectionPermissionRepository).should().deleteAll(List.of(userPermission));
        assertThat(collection.getStatus()).isEqualTo(com.opensource.docgrid.domain.collection.enums.CollectionStatus.DELETED);
    }

    @Test
    @DisplayName("하위 컬렉션이 있으면 삭제 시 하위 컬렉션과 문서 매핑까지 cascade로 함께 삭제된다")
    void deleteCollection_cascades_to_descendants() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection root = CollectionFixture.createCollection(owner);
        Long childId = 2L;
        DocumentCollection child = CollectionFixture.createChildCollection(owner, root, childId);
        List<Long> targetIds = List.of(CollectionFixture.COLLECTION_ID, childId);
        CollectionDocument childMapping = CollectionDocument.builder()
                .collection(child).document(CollectionFixture.createDocument(owner)).addedBy(owner)
                .addedAt(java.time.LocalDateTime.now()).build();

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(root));
        given(collectionRepository.findDescendantIdsInclusive(CollectionFixture.COLLECTION_ID)).willReturn(targetIds);
        given(collectionPermissionRepository.findAllByCollectionIdIn(targetIds)).willReturn(List.of());
        given(collectionDocumentRepository.findAllByCollectionIdIn(targetIds)).willReturn(List.of(childMapping));
        given(collectionRepository.findAllById(targetIds)).willReturn(List.of(root, child));

        collectionCommandService.deleteCollection(CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID);

        then(collectionDocumentRepository).should().deleteAll(List.of(childMapping));
        assertThat(root.getStatus()).isEqualTo(com.opensource.docgrid.domain.collection.enums.CollectionStatus.DELETED);
        assertThat(child.getStatus()).isEqualTo(com.opensource.docgrid.domain.collection.enums.CollectionStatus.DELETED);
    }

    @Test
    @DisplayName("컬렉션이 없으면 deleteCollection 시 COLLECTION_NOT_FOUND 예외가 발생한다")
    void deleteCollection_throws_when_collectionNotFound() {
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                collectionCommandService.deleteCollection(CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("소유자가 아닌 사용자가 삭제하면 PERMISSION_DENIED 예외가 발생한다")
    void deleteCollection_throws_when_notOwner() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Long otherUserId = 99L;

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        assertThatThrownBy(() ->
                collectionCommandService.deleteCollection(CollectionFixture.COLLECTION_ID, otherUserId))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    // ==================== removeDocument ====================

    @Test
    @DisplayName("소유자가 문서를 제거하면 collection_documents 삭제 및 캐시 무효화된다")
    void removeDocument_succeeds_when_owner() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Document document = CollectionFixture.createDocument(owner);
        CollectionPermission userPermission = PermissionFixture.createCollectionPermission(collection, owner);
        CollectionDocument cd = CollectionDocument.builder()
                .collection(collection).document(document).addedBy(owner)
                .addedAt(java.time.LocalDateTime.now()).build();

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(collectionDocumentRepository.findByCollectionIdAndDocumentId(
                CollectionFixture.COLLECTION_ID, CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(cd));
        given(collectionPermissionRepository.findAllByCollectionId(CollectionFixture.COLLECTION_ID))
                .willReturn(List.of(userPermission));

        collectionCommandService.removeDocument(
                CollectionFixture.COLLECTION_ID, CollectionFixture.DOCUMENT_ID, CollectionFixture.USER_ID);

        then(cacheService).should().bulkRevokeBySourcesForDocument(any(), any(), any());
        then(collectionDocumentRepository).should().delete(cd);
    }

    @Test
    @DisplayName("컬렉션에 해당 문서가 없으면 COLLECTION_DOCUMENT_NOT_FOUND 예외가 발생한다")
    void removeDocument_throws_when_documentNotInCollection() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(collectionDocumentRepository.findByCollectionIdAndDocumentId(
                CollectionFixture.COLLECTION_ID, CollectionFixture.DOCUMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                collectionCommandService.removeDocument(
                        CollectionFixture.COLLECTION_ID, CollectionFixture.DOCUMENT_ID, CollectionFixture.USER_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("소유자가 아닌 사용자가 문서를 제거하면 PERMISSION_DENIED 예외가 발생한다")
    void removeDocument_throws_when_notOwner() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Long otherUserId = 99L;

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        assertThatThrownBy(() ->
                collectionCommandService.removeDocument(
                        CollectionFixture.COLLECTION_ID, CollectionFixture.DOCUMENT_ID, otherUserId))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }
}
