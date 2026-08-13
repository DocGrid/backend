package com.opensource.docgrid.domain.document.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;

/**
 * Document가 인덱싱 완료 Version만 같은 문서의 현재 검색 대상으로 활성화하는지 검증한다.
 */
@DisplayName("Document 테스트")
class DocumentTest {

    private static final Long DOCUMENT_ID = 1L;

    @Test
    @DisplayName("같은 문서의 INDEXED Version을 현재 검색 대상으로 활성화한다")
    void activateIndexedVersion_updatesCurrentVersionAndStatus() {
        Document document = document(DOCUMENT_ID);
        DocumentVersion version = version(document, DocumentVersionStatus.EMBEDDING);
        version.markIndexed(java.time.LocalDateTime.of(2026, 7, 31, 12, 0));

        document.activateIndexedVersion(version);

        assertThat(document.getCurrentVersion()).isSameAs(version);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    }

    @Test
    @DisplayName("다른 문서의 Version은 활성화할 수 없다")
    void activateIndexedVersion_rejectsForeignVersion() {
        Document document = document(DOCUMENT_ID);
        Document otherDocument = document(2L);
        DocumentVersion version = version(otherDocument, DocumentVersionStatus.INDEXED);

        assertThatThrownBy(() -> document.activateIndexedVersion(version))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("현재 문서에 속한 Version만 활성화할 수 있습니다.");
        assertThat(document.getCurrentVersion()).isNull();
    }

    @Test
    @DisplayName("INDEXED가 아닌 Version은 활성화할 수 없다")
    void activateIndexedVersion_rejectsIncompleteVersion() {
        Document document = document(DOCUMENT_ID);
        DocumentVersion version = version(document, DocumentVersionStatus.EMBEDDING);

        assertThatThrownBy(() -> document.activateIndexedVersion(version))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("INDEXED 상태의 문서 버전만 활성화할 수 있습니다.");
        assertThat(document.getCurrentVersion()).isNull();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXING);
    }

    private Document document(Long id) {
        Document document = Document.builder()
            .title("문서")
            .status(DocumentStatus.INDEXING)
            .build();
        ReflectionTestUtils.setField(document, "id", id);
        return document;
    }

    private DocumentVersion version(Document document, DocumentVersionStatus status) {
        return DocumentVersion.builder()
            .document(document)
            .versionNo(1)
            .status(status)
            .build();
    }
}
