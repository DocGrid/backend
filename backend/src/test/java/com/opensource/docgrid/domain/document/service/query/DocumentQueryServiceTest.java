package com.opensource.docgrid.domain.document.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.document.converter.DocumentStatusConverter;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentStatusProjection;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentQueryService 테스트")
class DocumentQueryServiceTest {

    private static final Long USER_ID = 10L;
    private static final Long DOCUMENT_ID = 20L;

    @InjectMocks
    private DocumentQueryService service;

    @Mock private DocumentRepository documentRepository;
    @Mock private PermissionQueryService permissionQueryService;
    @Mock private DocumentStatusConverter documentStatusConverter;
    @Mock private DocumentStatusProjection projection;

    @Test
    @DisplayName("읽기 권한이 있고 상태가 일관되면 문서 상태를 반환한다")
    void getDocumentStatus_returnsResponse_when_statusIsConsistent() {
        DocumentStatusResponse expected = new DocumentStatusResponse(
            DOCUMENT_ID, DocumentStatus.INDEXED, null, null
        );
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findDocumentStatus(
            org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), anyCollection(), anyCollection()
        )).willReturn(List.of(projection));
        given(projection.getCurrentVersionNo()).willReturn(1);
        given(projection.getCurrentVersionStatus()).willReturn(DocumentVersionStatus.INDEXED);
        given(projection.getProcessingVersionNo()).willReturn(null);
        given(projection.getProcessingVersionStatus()).willReturn(null);
        given(projection.getProcessingJobStatus()).willReturn(null);
        given(documentStatusConverter.toResponse(projection)).willReturn(expected);

        DocumentStatusResponse result = service.getDocumentStatus(USER_ID, DOCUMENT_ID);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("문서 읽기 권한이 없으면 상태를 조회하지 않고 403 예외가 발생한다")
    void getDocumentStatus_throws_when_readPermissionIsDenied() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(false);

        assertThatThrownBy(() -> service.getDocumentStatus(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
        then(documentRepository).should(never()).findDocumentStatus(
            org.mockito.ArgumentMatchers.anyLong(), anyCollection(), anyCollection()
        );
    }

    @Test
    @DisplayName("상태 조회 결과가 없으면 문서 없음 예외가 발생한다")
    void getDocumentStatus_throws_when_documentDoesNotExist() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findDocumentStatus(
            org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), anyCollection(), anyCollection()
        )).willReturn(List.of());

        assertThatThrownBy(() -> service.getDocumentStatus(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("처리 중 버전에 활성 Job이 없으면 상태 불일치 예외가 발생한다")
    void getDocumentStatus_throws_when_processingJobIsMissing() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findDocumentStatus(
            org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), anyCollection(), anyCollection()
        )).willReturn(List.of(projection));
        given(projection.getCurrentVersionNo()).willReturn(null);
        given(projection.getCurrentVersionStatus()).willReturn(null);
        given(projection.getProcessingVersionNo()).willReturn(2);
        given(projection.getProcessingVersionStatus()).willReturn(DocumentVersionStatus.PARSING);
        given(projection.getProcessingJobStatus()).willReturn(null);

        assertThatThrownBy(() -> service.getDocumentStatus(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INDEXING_STATUS_INCONSISTENT);
    }

    @Test
    @DisplayName("활성 Job이 중복되어 조회 행이 여러 개면 상태 불일치 예외가 발생한다")
    void getDocumentStatus_throws_when_multipleStatusRowsExist() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findDocumentStatus(
            org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), anyCollection(), anyCollection()
        )).willReturn(List.of(projection, projection));

        assertThatThrownBy(() -> service.getDocumentStatus(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INDEXING_STATUS_INCONSISTENT);
    }

    @Test
    @DisplayName("삭제된 문서는 문서 없음 예외가 발생한다")
    void getDocumentStatus_throws_when_documentIsDeleted() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findDocumentStatus(
            org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), anyCollection(), anyCollection()
        )).willReturn(List.of(projection));
        given(projection.getDocumentStatus()).willReturn(DocumentStatus.DELETED);
        given(projection.getCurrentVersionNo()).willReturn(null);
        given(projection.getCurrentVersionStatus()).willReturn(null);
        given(projection.getProcessingVersionNo()).willReturn(null);
        given(projection.getProcessingVersionStatus()).willReturn(null);
        given(projection.getProcessingJobStatus()).willReturn(null);

        assertThatThrownBy(() -> service.getDocumentStatus(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }
}
