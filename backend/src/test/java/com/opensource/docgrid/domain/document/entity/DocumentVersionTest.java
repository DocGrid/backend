package com.opensource.docgrid.domain.document.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;

/**
 * Document Version 파이프라인의 UPLOADED·PARSING·CHUNKED·EMBEDDING·INDEXED·FAILED 상태 전이
 * Guard를 검증한다.
 *
 * <p>Command Service를 우회한 잘못된 상태 변경은 즉시 실패하고 정상 순서만 허용되는지 확인한다.
 */
@DisplayName("DocumentVersion 테스트")
class DocumentVersionTest {

    @Test
    @DisplayName("UPLOADED에서 PARSING을 거쳐 CHUNKED로 전이한다")
    void parsingAndChunked_followExpectedOrder() {
        DocumentVersion version = version(DocumentVersionStatus.UPLOADED);

        version.markParsing();
        assertThat(version.getStatus()).isEqualTo(DocumentVersionStatus.PARSING);

        version.markChunked();
        assertThat(version.getStatus()).isEqualTo(DocumentVersionStatus.CHUNKED);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionStatus.class, names = "UPLOADED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("UPLOADED가 아닌 상태에서는 PARSING 전이를 거부한다")
    void markParsing_rejectsUnexpectedStatus(DocumentVersionStatus status) {
        DocumentVersion version = version(status);

        assertThatThrownBy(version::markParsing)
            .isInstanceOf(IllegalStateException.class);
        assertThat(version.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionStatus.class, names = "PARSING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("PARSING이 아닌 상태에서는 CHUNKED 전이를 거부한다")
    void markChunked_rejectsUnexpectedStatus(DocumentVersionStatus status) {
        DocumentVersion version = version(status);

        assertThatThrownBy(version::markChunked)
            .isInstanceOf(IllegalStateException.class);
        assertThat(version.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("CHUNKED에서 EMBEDDING으로 전이한다")
    void markEmbedding_transitionsFromChunked() {
        DocumentVersion version = version(DocumentVersionStatus.CHUNKED);

        version.markEmbedding();

        assertThat(version.getStatus()).isEqualTo(DocumentVersionStatus.EMBEDDING);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionStatus.class, names = "CHUNKED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("CHUNKED가 아닌 상태에서는 EMBEDDING 전이를 거부한다")
    void markEmbedding_rejectsUnexpectedStatus(DocumentVersionStatus status) {
        DocumentVersion version = version(status);

        assertThatThrownBy(version::markEmbedding)
            .isInstanceOf(IllegalStateException.class);
        assertThat(version.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("EMBEDDING Version을 INDEXED로 전환하고 완료 시각을 기록한다")
    void markIndexed_transitionsFromEmbedding() {
        DocumentVersion version = version(DocumentVersionStatus.EMBEDDING);
        LocalDateTime indexedAt = LocalDateTime.of(2026, 7, 29, 12, 0);

        version.markIndexed(indexedAt);

        assertThat(version.getStatus()).isEqualTo(DocumentVersionStatus.INDEXED);
        assertThat(version.getIndexedAt()).isEqualTo(indexedAt);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionStatus.class, names = "EMBEDDING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("EMBEDDING이 아닌 상태에서는 INDEXED 전이를 거부한다")
    void markIndexed_rejectsUnexpectedStatus(DocumentVersionStatus status) {
        DocumentVersion version = version(status);

        assertThatThrownBy(() -> version.markIndexed(LocalDateTime.of(2026, 7, 29, 12, 0)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(version.getStatus()).isEqualTo(status);
        assertThat(version.getIndexedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionStatus.class, names = {"UPLOADED", "PARSING", "CHUNKED", "EMBEDDING"})
    @DisplayName("처리 중인 Version은 FAILED로 종결할 수 있다")
    void markFailed_transitionsProcessingStatus(DocumentVersionStatus status) {
        DocumentVersion version = version(status);

        version.markFailed();

        assertThat(version.getStatus()).isEqualTo(DocumentVersionStatus.FAILED);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionStatus.class, names = {"INDEXED", "FAILED"})
    @DisplayName("완료되거나 이미 실패한 Version은 다시 FAILED로 전환할 수 없다")
    void markFailed_rejectsTerminalStatus(DocumentVersionStatus status) {
        DocumentVersion version = version(status);

        assertThatThrownBy(version::markFailed)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("처리 중인 문서 버전만 FAILED로 전환할 수 있습니다.");
        assertThat(version.getStatus()).isEqualTo(status);
    }

    private DocumentVersion version(DocumentVersionStatus status) {
        return DocumentVersion.builder()
            .versionNo(1)
            .status(status)
            .build();
    }
}
