package com.opensource.docgrid.domain.document.service.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 새 버전 접수 전 검증 규칙을 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentVersionUploadService 단위 테스트")
class DocumentVersionUploadServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long DOCUMENT_ID = 11L;
    private static final String FILE_HASH = "hash";

    @Mock private UserRepository userRepository;
    @Mock private FileObjectResolutionService fileObjectResolutionService;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingModelQueryService embeddingModelQueryService;
    @Mock private SyncEventWriter syncEventWriter;

    @InjectMocks private DocumentVersionUploadService documentVersionUploadService;

    @Test
    @DisplayName("예외 케이스: 삭제된 문서에 새 버전을 올리면 문서를 찾을 수 없다고 응답한다")
    void prepare_throwsNotFound_whenDocumentDeleted() {
        // 상태 분기까지 내려가면 이 경우만 409가 되어 나머지 조회·수정 API의 404와 어긋난다.
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(deletedDocument()));

        assertThatThrownBy(() -> documentVersionUploadService.prepare(
            USER_ID, DOCUMENT_ID, validatedFile(), FILE_HASH
        ))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("예외 케이스: 소유자가 아니면 삭제 여부보다 먼저 권한 없음으로 막는다")
    void prepare_throwsPermissionDenied_whenNotOwner() {
        // 삭제 검사가 앞서면 타인의 삭제된 문서 존재 여부가 드러나므로 순서를 고정한다.
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(deletedDocument()));

        assertThatThrownBy(() -> documentVersionUploadService.prepare(
            USER_ID + 1, DOCUMENT_ID, validatedFile(), FILE_HASH
        ))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    private Document deletedDocument() {
        User owner = User.builder().build();
        ReflectionTestUtils.setField(owner, "id", USER_ID);

        Document document = Document.builder()
            .owner(owner)
            .title("삭제된 문서")
            .documentType(DocumentType.PDF)
            .sourceType(DocumentSourceType.UPLOAD)
            .status(DocumentStatus.DELETED)
            .visibility(VisibilityType.PRIVATE)
            .build();
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        return document;
    }

    private ValidatedFile validatedFile() {
        return new ValidatedFile("new.pdf", "pdf", "application/pdf", 100L, DocumentType.PDF);
    }
}
