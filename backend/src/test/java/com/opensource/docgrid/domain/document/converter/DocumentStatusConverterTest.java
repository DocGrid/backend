package com.opensource.docgrid.domain.document.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentStatusProjection;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentStatusConverter 테스트")
class DocumentStatusConverterTest {

    @InjectMocks
    private DocumentStatusConverter converter;

    @Mock
    private DocumentStatusProjection projection;

    @Test
    @DisplayName("검색 가능한 현재 버전과 처리 중 버전을 함께 변환한다")
    void toResponse_convertsCurrentAndProcessingVersions() {
        given(projection.getDocumentId()).willReturn(10L);
        given(projection.getDocumentStatus()).willReturn(DocumentStatus.INDEXED);
        given(projection.getCurrentVersionNo()).willReturn(1);
        given(projection.getCurrentVersionStatus()).willReturn(DocumentVersionStatus.INDEXED);
        given(projection.getProcessingVersionNo()).willReturn(2);
        given(projection.getProcessingVersionStatus()).willReturn(DocumentVersionStatus.PARSING);
        given(projection.getProcessingJobStatus()).willReturn(EmbeddingJobStatus.PROCESSING);

        DocumentStatusResponse response = converter.toResponse(projection);

        assertThat(response.documentId()).isEqualTo(10L);
        assertThat(response.documentStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(response.currentVersion().versionNo()).isEqualTo(1);
        assertThat(response.currentVersion().status()).isEqualTo(DocumentVersionStatus.INDEXED);
        assertThat(response.processingVersion().versionNo()).isEqualTo(2);
        assertThat(response.processingVersion().status()).isEqualTo(DocumentVersionStatus.PARSING);
        assertThat(response.processingVersion().jobStatus()).isEqualTo(EmbeddingJobStatus.PROCESSING);
    }

    @Test
    @DisplayName("최초 버전이 처리 중이면 검색 가능한 현재 버전을 null로 변환한다")
    void toResponse_returnsNullCurrentVersion_when_initialVersionIsProcessing() {
        given(projection.getDocumentId()).willReturn(10L);
        given(projection.getDocumentStatus()).willReturn(DocumentStatus.UPLOADED);
        given(projection.getCurrentVersionStatus()).willReturn(DocumentVersionStatus.UPLOADED);
        given(projection.getProcessingVersionNo()).willReturn(1);
        given(projection.getProcessingVersionStatus()).willReturn(DocumentVersionStatus.UPLOADED);
        given(projection.getProcessingJobStatus()).willReturn(EmbeddingJobStatus.PENDING);

        DocumentStatusResponse response = converter.toResponse(projection);

        assertThat(response.currentVersion()).isNull();
        assertThat(response.processingVersion().versionNo()).isEqualTo(1);
        assertThat(response.processingVersion().jobStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
    }

    @Test
    @DisplayName("처리 중인 버전이 없으면 processingVersion을 null로 변환한다")
    void toResponse_returnsNullProcessingVersion_when_noVersionIsProcessing() {
        given(projection.getDocumentId()).willReturn(10L);
        given(projection.getDocumentStatus()).willReturn(DocumentStatus.INDEXED);
        given(projection.getCurrentVersionNo()).willReturn(1);
        given(projection.getCurrentVersionStatus()).willReturn(DocumentVersionStatus.INDEXED);
        given(projection.getProcessingVersionNo()).willReturn(null);

        DocumentStatusResponse response = converter.toResponse(projection);

        assertThat(response.currentVersion()).isNotNull();
        assertThat(response.processingVersion()).isNull();
    }
}
