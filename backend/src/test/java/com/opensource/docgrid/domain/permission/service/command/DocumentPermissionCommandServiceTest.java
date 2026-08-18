package com.opensource.docgrid.domain.permission.service.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.converter.PermissionConverter;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.entity.DocumentPermission;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.permission.fixture.PermissionFixture;
import com.opensource.docgrid.domain.permission.repository.DocumentPermissionRepository;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentPermissionCommandService 단위 테스트")
class DocumentPermissionCommandServiceTest {

    @InjectMocks
    private DocumentPermissionCommandService service;

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentPermissionRepository documentPermissionRepository;
    @Mock private UserDocumentAccessCacheService cacheService;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private PermissionConverter permissionConverter;
    @Mock private PermissionQueryService permissionQueryService;
    @Mock private SyncEventWriter syncEventWriter;

    @Test
    @DisplayName("USER 대상 문서 권한을 부여하면 캐시도 함께 갱신된다")
    void grantPermission_user_updatesCache() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.USER, CollectionFixture.USER_ID, null, null, PermissionType.READ, null);

        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(permissionQueryService.canAdminDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID)).willReturn(true);
        given(userRepository.findById(CollectionFixture.USER_ID)).willReturn(Optional.of(owner));
        given(userRepository.getReferenceById(CollectionFixture.USER_ID)).willReturn(owner);
        given(permissionConverter.toDocumentPermissionResponse(any())).willReturn(null);

        service.grantPermission(CollectionFixture.DOCUMENT_ID, CollectionFixture.USER_ID, request);

        then(documentPermissionRepository).should().save(any(DocumentPermission.class));
        then(cacheService).should().grantUserPermission(any(), any(), any(boolean.class),
                any(boolean.class), any(boolean.class), any(), any(), any());
    }

    @Test
    @DisplayName("ROLE 대상 문서 권한을 부여하면 캐시 갱신 없이 저장만 된다")
    void grantPermission_role_noCacheUpdate() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.ROLE, null, PermissionFixture.ROLE_ID, null, PermissionType.READ, null);

        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(permissionQueryService.canAdminDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID)).willReturn(true);
        given(roleRepository.findById(PermissionFixture.ROLE_ID)).willReturn(Optional.of(PermissionFixture.createRole()));
        given(userRepository.getReferenceById(CollectionFixture.USER_ID)).willReturn(owner);
        given(permissionConverter.toDocumentPermissionResponse(any())).willReturn(null);

        service.grantPermission(CollectionFixture.DOCUMENT_ID, CollectionFixture.USER_ID, request);

        then(documentPermissionRepository).should().save(any(DocumentPermission.class));
        then(cacheService).should(never()).grantUserPermission(any(), any(), any(boolean.class),
                any(boolean.class), any(boolean.class), any(), any(), any());
    }

    @Test
    @DisplayName("USER role을 대상으로 지정하면 ROLE_NOT_GRANTABLE 예외가 발생한다")
    void grantPermission_throws_when_targetRoleIsUserRole() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.ROLE, null, PermissionFixture.USER_ROLE_ID, null, PermissionType.READ, null);

        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(permissionQueryService.canAdminDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID)).willReturn(true);
        given(roleRepository.findById(PermissionFixture.USER_ROLE_ID)).willReturn(Optional.of(PermissionFixture.createUserRole()));

        assertThatThrownBy(() -> service.grantPermission(
                CollectionFixture.DOCUMENT_ID, CollectionFixture.USER_ID, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROLE_NOT_GRANTABLE);
        then(documentPermissionRepository).should(never()).save(any(DocumentPermission.class));
    }

    @Test
    @DisplayName("문서가 없으면 DOCUMENT_NOT_FOUND 예외가 발생한다")
    void grantPermission_throws_when_documentNotFound() {
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.empty());
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.USER, CollectionFixture.USER_ID, null, null, PermissionType.READ, null);

        assertThatThrownBy(() -> service.grantPermission(
                CollectionFixture.DOCUMENT_ID, CollectionFixture.USER_ID, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("소유자가 아닌 사용자가 권한을 부여하면 PERMISSION_DENIED 예외가 발생한다")
    void grantPermission_throws_when_notOwner() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.USER, CollectionFixture.USER_ID, null, null, PermissionType.READ, null);

        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.grantPermission(
                CollectionFixture.DOCUMENT_ID, otherUserId, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    // ==================== revokePermission ====================

    @Test
    @DisplayName("USER 권한을 회수하면 캐시도 무효화된다")
    void revokePermission_user_invalidatesCache() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        DocumentPermission permission = PermissionFixture.createDocumentPermission(document, owner);

        given(documentPermissionRepository.findById(PermissionFixture.PERMISSION_ID))
                .willReturn(Optional.of(permission));
        given(permissionQueryService.canAdminDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        service.revokePermission(CollectionFixture.DOCUMENT_ID, PermissionFixture.PERMISSION_ID,
                CollectionFixture.USER_ID);

        then(cacheService).should().revokeUserPermission(any(), any(), any(), any());
        then(documentPermissionRepository).should().delete(permission);
    }

    @Test
    @DisplayName("권한이 없으면 DOCUMENT_PERMISSION_NOT_FOUND 예외가 발생한다")
    void revokePermission_throws_when_permissionNotFound() {
        given(documentPermissionRepository.findById(PermissionFixture.PERMISSION_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokePermission(
                CollectionFixture.DOCUMENT_ID, PermissionFixture.PERMISSION_ID, CollectionFixture.USER_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_PERMISSION_NOT_FOUND);
    }

    @Test
    @DisplayName("소유자가 아닌 사용자가 권한을 회수하면 PERMISSION_DENIED 예외가 발생한다")
    void revokePermission_throws_when_notOwner() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        DocumentPermission permission = PermissionFixture.createDocumentPermission(document, owner);
        Long otherUserId = 99L;

        given(documentPermissionRepository.findById(PermissionFixture.PERMISSION_ID))
                .willReturn(Optional.of(permission));

        assertThatThrownBy(() -> service.revokePermission(
                CollectionFixture.DOCUMENT_ID, PermissionFixture.PERMISSION_ID, otherUserId))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("targetType과 ID 필드 조합이 맞지 않으면 INVALID_TARGET_TYPE 예외가 발생한다")
    void grantPermission_throws_when_invalidTargetType() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.ROLE, null, null, null, PermissionType.READ, null);

        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(permissionQueryService.canAdminDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        assertThatThrownBy(() -> service.grantPermission(
                CollectionFixture.DOCUMENT_ID, CollectionFixture.USER_ID, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TARGET_TYPE);
    }
}
