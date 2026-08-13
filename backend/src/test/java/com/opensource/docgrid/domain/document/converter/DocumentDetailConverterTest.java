package com.opensource.docgrid.domain.document.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.dto.response.DocumentDetailResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.user.entity.User;

/**
 * 문서와 현재 버전 Entity가 저장소 내부 위치 없이 상세 응답으로 변환되는지 검증한다.
 */
@DisplayName("DocumentDetailConverter 테스트")
class DocumentDetailConverterTest {

    private final DocumentDetailConverter converter = new DocumentDetailConverter();

    @Test
    @DisplayName("문서 Metadata와 현재 버전 파일 정보를 상세 응답으로 변환한다")
    void toResponse_convertsDocumentAndCurrentVersion() {
        Document document = mock(Document.class);
        DocumentVersion currentVersion = mock(DocumentVersion.class);
        FileObject fileObject = mock(FileObject.class);
        User owner = mock(User.class);
        given(document.getId()).willReturn(1L);
        given(document.getTitle()).willReturn("운영 가이드");
        given(document.getDocumentType()).willReturn(DocumentType.PDF);
        given(document.getSourceType()).willReturn(DocumentSourceType.UPLOAD);
        given(document.getStatus()).willReturn(DocumentStatus.INDEXED);
        given(document.getVisibility()).willReturn(VisibilityType.PRIVATE);
        given(document.getOwner()).willReturn(owner);
        given(owner.getId()).willReturn(2L);
        given(owner.getName()).willReturn("소유자");
        given(document.getCurrentVersion()).willReturn(currentVersion);
        given(currentVersion.getId()).willReturn(3L);
        given(currentVersion.getVersionNo()).willReturn(4);
        given(currentVersion.getStatus()).willReturn(DocumentVersionStatus.INDEXED);
        given(currentVersion.getFileObject()).willReturn(fileObject);
        given(fileObject.getOriginalFilename()).willReturn("guide.pdf");
        given(fileObject.getContentType()).willReturn("application/pdf");
        given(fileObject.getFileSize()).willReturn(1024L);

        DocumentDetailResponse result = converter.toResponse(document, true);

        assertThat(result.documentId()).isEqualTo(1L);
        assertThat(result.ownerUserId()).isEqualTo(2L);
        assertThat(result.ownerName()).isEqualTo("소유자");
        assertThat(result.contentAvailable()).isTrue();
        assertThat(result.currentVersion().documentVersionId()).isEqualTo(3L);
        assertThat(result.currentVersion().originalFilename()).isEqualTo("guide.pdf");
        assertThat(result.currentVersion().fileSize()).isEqualTo(1024L);
    }
}
