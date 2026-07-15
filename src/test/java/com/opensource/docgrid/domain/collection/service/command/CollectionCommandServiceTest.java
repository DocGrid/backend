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

import com.opensource.docgrid.domain.collection.converter.CollectionConverter;
import com.opensource.docgrid.domain.collection.dto.request.AddDocumentRequest;
import com.opensource.docgrid.domain.collection.dto.request.CreateCollectionRequest;
import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.fixture.CollectionFixture;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
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
        given(collectionConverter.toResponse(any(DocumentCollection.class))).willReturn(expected);

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
        given(collectionConverter.toResponse(any(DocumentCollection.class))).willReturn(expected);

        CollectionResponse result = collectionCommandService.createCollection(CollectionFixture.USER_ID, request);

        assertThat(result.visibility()).isEqualTo(VisibilityType.PRIVATE);
    }

    @Test
    @DisplayName("존재하는 상위 컬렉션 ID를 지정하면 parentCollection이 설정된 컬렉션이 생성된다")
    void createCollection_succeeds_with_parentCollection() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection parent = CollectionFixture.createCollection(owner);
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        CreateCollectionRequest request = new CreateCollectionRequest(
                "하위 컬렉션", null, CollectionFixture.COLLECTION_ID, VisibilityType.PRIVATE
        );
        given(userRepository.getReferenceById(CollectionFixture.USER_ID)).willReturn(owner);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(parent));
        given(collectionConverter.toResponse(any(DocumentCollection.class))).willReturn(expected);

        collectionCommandService.createCollection(CollectionFixture.USER_ID, request);

        then(collectionRepository).should().findById(CollectionFixture.COLLECTION_ID);
        then(collectionRepository).should().save(any(DocumentCollection.class));
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
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(collectionDocumentRepository.existsByCollectionIdAndDocumentId(
                CollectionFixture.COLLECTION_ID, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        assertThatThrownBy(() -> collectionCommandService.addDocument(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, new AddDocumentRequest(CollectionFixture.DOCUMENT_ID)))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_DOCUMENT_ALREADY_EXISTS);
    }
}
