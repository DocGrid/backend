package com.opensource.docgrid.domain.permission.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.collection.fixture.CollectionFixture;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.permission.repository.DocumentPermissionRepository;
import com.opensource.docgrid.domain.permission.repository.UserDocumentAccessCacheRepository;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionQueryService 단위 테스트")
class PermissionQueryServiceTest {

    @InjectMocks
    private PermissionQueryService service;

    @Mock private CollectionRepository collectionRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private UserDocumentAccessCacheRepository cacheRepository;
    @Mock private DocumentPermissionRepository documentPermissionRepository;
    @Mock private CollectionPermissionRepository collectionPermissionRepository;

    // ==================== canReadDocument ====================

    @Test
    @DisplayName("소유자는 canReadDocument가 true다")
    void canReadDocument_owner_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        boolean result = service.canReadDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(cacheRepository).should(never()).existsValidReadCache(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("PUBLIC 문서는 누구나 canReadDocument가 true다")
    void canReadDocument_public_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        org.springframework.test.util.ReflectionTestUtils.setField(document, "visibility", VisibilityType.PUBLIC);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(cacheRepository).should(never()).existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("유효한 USER 캐시가 있으면 canReadDocument가 true다")
    void canReadDocument_validCache_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(documentPermissionRepository).should(never()).existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("ROLE live 권한이 있으면 canReadDocument가 true다")
    void canReadDocument_roleLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(documentPermissionRepository).should(never()).existsDeptReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("DEPARTMENT live 권한이 있으면 canReadDocument가 true다")
    void canReadDocument_deptLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleReadPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("모든 단계를 통과하지 못하면 canReadDocument가 false다")
    void canReadDocument_noPermission_returnsFalse() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleReadPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsDeptReadPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("문서가 없으면 DOCUMENT_NOT_FOUND 예외가 발생한다")
    void canReadDocument_documentNotFound_throwsException() {
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.canReadDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    // ==================== canAdminCollection ====================

    @Test
    @DisplayName("컬렉션 소유자는 canAdminCollection이 true다")
    void canAdminCollection_owner_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));

        boolean result = service.canAdminCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
        then(collectionPermissionRepository).should(never()).existsUserAdminPermission(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);
    }

    @Test
    @DisplayName("USER 권한으로 admin이 부여된 경우 canAdminCollection이 true다")
    void canAdminCollection_userPermission_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserAdminPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(true);

        boolean result = service.canAdminCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("권한이 없으면 canAdminCollection이 false다")
    void canAdminCollection_noPermission_returnsFalse() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserAdminPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);

        boolean result = service.canAdminCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isFalse();
    }
}
