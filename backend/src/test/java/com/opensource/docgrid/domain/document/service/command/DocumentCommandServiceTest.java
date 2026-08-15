package com.opensource.docgrid.domain.document.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.dto.request.UpdateDocumentMetadataRequest;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 문서 Metadata 변경과 soft delete의 권한 및 상태 전이 규칙을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentCommandService 단위 테스트")
class DocumentCommandServiceTest {

    private static final Long USER_ID = 20L;
    private static final Long DOCUMENT_ID = 10L;
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-15T03:00:00Z");
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

    @Mock private DocumentRepository documentRepository;
    @Mock private PermissionQueryService permissionQueryService;
    @Mock private SyncEventWriter syncEventWriter;

    private DocumentCommandService documentCommandService;

    @BeforeEach
    void setUp() {
        documentCommandService = new DocumentCommandService(
            documentRepository,
            permissionQueryService,
            syncEventWriter,
            Clock.fixed(FIXED_INSTANT, ZONE_ID)
        );
    }

    @Test
    @DisplayName("WRITE 권한이 있으면 제목을 정리하고 공백 설명을 제거한다")
    void updateMetadata_updatesCurrentMetadata_whenUserCanWrite() {
        Document document = activeDocument();
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(permissionQueryService.canWriteDocument(USER_ID, DOCUMENT_ID)).willReturn(true);

        documentCommandService.updateMetadata(
            USER_ID,
            DOCUMENT_ID,
            new UpdateDocumentMetadataRequest("  변경한 제목  ", "   ")
        );

        assertThat(document.getTitle()).isEqualTo("변경한 제목");
        assertThat(document.getDescription()).isNull();
        then(syncEventWriter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("WRITE 권한이 없으면 문서 정보를 변경하지 않는다")
    void updateMetadata_throws_whenUserCannotWrite() {
        Document document = activeDocument();
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(permissionQueryService.canWriteDocument(USER_ID, DOCUMENT_ID)).willReturn(false);

        assertThatThrownBy(() -> documentCommandService.updateMetadata(
            USER_ID,
            DOCUMENT_ID,
            new UpdateDocumentMetadataRequest("변경한 제목", "변경한 설명")
        ))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);

        assertThat(document.getTitle()).isEqualTo("기존 제목");
        assertThat(document.getDescription()).isEqualTo("기존 설명");
    }

    @Test
    @DisplayName("ADMIN 권한이 있으면 문서를 soft delete하고 Outbox Event를 기록한다")
    void deleteDocument_marksDeletedAndRecordsEvent_whenUserCanAdmin() {
        Document document = activeDocument();
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(permissionQueryService.canAdminDocument(USER_ID, DOCUMENT_ID)).willReturn(true);

        documentCommandService.deleteDocument(USER_ID, DOCUMENT_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.DELETED);
        assertThat(document.getDeletedAt()).isEqualTo(LocalDateTime.ofInstant(FIXED_INSTANT, ZONE_ID));
        then(syncEventWriter).should().recordDocumentDeleted(document);
    }

    @Test
    @DisplayName("ADMIN 권한이 없으면 문서를 삭제하거나 Event를 기록하지 않는다")
    void deleteDocument_throws_whenUserCannotAdmin() {
        Document document = activeDocument();
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(permissionQueryService.canAdminDocument(USER_ID, DOCUMENT_ID)).willReturn(false);

        assertThatThrownBy(() -> documentCommandService.deleteDocument(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        then(syncEventWriter).should(never()).recordDocumentDeleted(document);
    }

    @Test
    @DisplayName("이미 삭제된 문서는 찾을 수 없는 문서로 처리한다")
    void deleteDocument_throwsNotFound_whenAlreadyDeleted() {
        Document document = activeDocument();
        document.markDeleted(LocalDateTime.of(2026, 8, 14, 12, 0));
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> documentCommandService.deleteDocument(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);

        then(permissionQueryService).shouldHaveNoInteractions();
        then(syncEventWriter).shouldHaveNoInteractions();
    }

    private Document activeDocument() {
        Document document = Document.builder()
            .title("기존 제목")
            .description("기존 설명")
            .status(DocumentStatus.INDEXED)
            .build();
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        return document;
    }
}
