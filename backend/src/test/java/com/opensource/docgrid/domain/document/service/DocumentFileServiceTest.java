package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.document.service.query.DocumentFileSnapshot;
import com.opensource.docgrid.domain.document.service.query.DocumentQueryService;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 원본 파일 조회가 DB Snapshot 이후 저장소 Byte를 결합하고 크기 불일치를 차단하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentFileService 테스트")
class DocumentFileServiceTest {

    private static final Long USER_ID = 10L;
    private static final Long DOCUMENT_ID = 20L;
    private static final StoredFile STORED_FILE = new StoredFile("documents", "objects/guide.pdf");

    @InjectMocks
    private DocumentFileService service;

    @Mock private DocumentQueryService documentQueryService;
    @Mock private FileStorageService fileStorageService;

    @Test
    @DisplayName("조회 Snapshot의 원본 파일을 읽어 다운로드 응답을 반환한다")
    void getDocumentFile_returnsStorageContentAndMetadata() {
        byte[] content = {1, 2, 3};
        given(documentQueryService.getDocumentFileSnapshot(USER_ID, DOCUMENT_ID)).willReturn(
            new DocumentFileSnapshot(STORED_FILE, "guide.pdf", "application/pdf", content.length)
        );
        given(fileStorageService.read(STORED_FILE)).willReturn(content);

        DocumentFileDownload result = service.getDocumentFile(USER_ID, DOCUMENT_ID);

        assertThat(result.content()).isSameAs(content);
        assertThat(result.originalFilename()).isEqualTo("guide.pdf");
        assertThat(result.contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("저장된 Byte 크기가 Metadata와 다르면 저장소 오류가 발생한다")
    void getDocumentFile_throws_whenFileSizeIsInconsistent() {
        given(documentQueryService.getDocumentFileSnapshot(USER_ID, DOCUMENT_ID)).willReturn(
            new DocumentFileSnapshot(STORED_FILE, "guide.pdf", "application/pdf", 4L)
        );
        given(fileStorageService.read(STORED_FILE)).willReturn(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.getDocumentFile(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_STORAGE_FAILED);
    }
}
