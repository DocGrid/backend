package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.opensource.docgrid.domain.document.config.DocumentChunkingProperties;

/**
 * Unicode Code Point 기반 고정 크기 Chunk 경계와 계산 값의 결정성을 검증한다.
 *
 * <p>Overlap 불변식, 마지막 Chunk 종료, Emoji 경계, Token 추정치와 SHA-256 Hash 계약을 확인한다.
 */
@DisplayName("FixedSizeChunker 테스트")
class FixedSizeChunkerTest {

    private DocumentChunkingProperties properties;
    private FixedSizeChunker chunker;

    @BeforeEach
    void setUp() {
        properties = new DocumentChunkingProperties();
        properties.setChunkSize(4);
        properties.setOverlap(1);
        chunker = new FixedSizeChunker(properties);
    }

    @Test
    @DisplayName("Chunk 크기 이하의 원문은 Index 0의 Chunk 한 개다")
    void chunk_createsSingleDraft_when_textFitsChunk() {
        List<DocumentChunkDraft> shorter = chunker.chunk("abc");
        List<DocumentChunkDraft> exact = chunker.chunk("abcd");

        assertThat(shorter).hasSize(1);
        assertThat(shorter.get(0))
            .extracting(
                DocumentChunkDraft::chunkIndex,
                DocumentChunkDraft::chunkText,
                DocumentChunkDraft::charStart,
                DocumentChunkDraft::charEnd
            )
            .containsExactly(0, "abc", 0, 3);
        assertThat(exact).hasSize(1);
        assertThat(exact.get(0).charEnd()).isEqualTo(4);
    }

    @Test
    @DisplayName("한 Code Point 초과 시 설정된 Overlap에서 두 번째 Chunk가 시작한다")
    void chunk_startsNextDraftAtOverlapBoundary() {
        List<DocumentChunkDraft> drafts = chunker.chunk("abcde");

        assertThat(drafts).hasSize(2);
        assertThat(drafts)
            .extracting(
                DocumentChunkDraft::chunkIndex,
                DocumentChunkDraft::chunkText,
                DocumentChunkDraft::charStart,
                DocumentChunkDraft::charEnd
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, "abcd", 0, 4),
                org.assertj.core.groups.Tuple.tuple(1, "de", 3, 5)
            );
    }

    @Test
    @DisplayName("Emoji를 UTF-16 Surrogate Pair 중간에서 분할하지 않는다")
    void chunk_usesUnicodeCodePointBoundaries() {
        properties.setChunkSize(2);
        properties.setOverlap(0);

        List<DocumentChunkDraft> drafts = chunker.chunk("A😀BC");

        assertThat(drafts)
            .extracting(DocumentChunkDraft::chunkText, DocumentChunkDraft::charStart, DocumentChunkDraft::charEnd)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("A😀", 0, 2),
                org.assertj.core.groups.Tuple.tuple("BC", 2, 4)
            );
    }

    @Test
    @DisplayName("공백 기반 Token 수와 비영속 필드를 결정적으로 계산한다")
    void chunk_estimatesTokensAndKeepsOptionalFieldsEmpty() {
        properties.setChunkSize(100);
        properties.setOverlap(0);

        DocumentChunkDraft draft = chunker.chunk(" 하나\t둘\n셋  ").get(0);

        assertThat(draft.tokenCount()).isEqualTo(3);
        assertThat(draft.pageNo()).isNull();
        assertThat(draft.sectionTitle()).isNull();
        assertThat(draft.metadataJson()).isNull();
        assertThat(draft.contentHash()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Segment 경계를 넘지 않고 Page·Section Metadata와 전역 Offset을 복사한다")
    void chunk_preservesSegmentBoundariesAndMetadata() {
        ParsedDocument parsedDocument = new ParsedDocument(List.of(
            new ParsedDocumentSegment("abcde", 1, null, "{\"source\":\"pdf\"}"),
            new ParsedDocumentSegment("wxyz", null, "Section B", null)
        ));

        List<DocumentChunkDraft> drafts = chunker.chunk(parsedDocument);

        assertThat(drafts)
            .extracting(
                DocumentChunkDraft::chunkIndex,
                DocumentChunkDraft::chunkText,
                DocumentChunkDraft::charStart,
                DocumentChunkDraft::charEnd
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, "abcd", 0, 4),
                org.assertj.core.groups.Tuple.tuple(1, "de", 3, 5),
                org.assertj.core.groups.Tuple.tuple(2, "wxyz", 6, 10)
            );
        assertThat(drafts.subList(0, 2)).allSatisfy(draft -> {
            assertThat(draft.pageNo()).isEqualTo(1);
            assertThat(draft.metadataJson()).isEqualTo("{\"source\":\"pdf\"}");
        });
        assertThat(drafts.get(2).sectionTitle()).isEqualTo("Section B");
    }

    @Test
    @DisplayName("같은 Text는 같은 Draft와 Hash를 만들고 한 글자 차이는 Hash를 바꾼다")
    void chunk_isDeterministic() {
        List<DocumentChunkDraft> first = chunker.chunk("abcdefgh");
        List<DocumentChunkDraft> second = chunker.chunk("abcdefgh");
        List<DocumentChunkDraft> changed = chunker.chunk("abcdefgi");

        assertThat(second).isEqualTo(first);
        assertThat(changed.get(0).contentHash()).isEqualTo(first.get(0).contentHash());
        assertThat(changed.get(2).contentHash()).isNotEqualTo(first.get(2).contentHash());
    }

    @Test
    @DisplayName("원문 끝에 도달하면 Overlap 전용 Chunk를 추가하지 않는다")
    void chunk_stopsAtTextEnd() {
        assertThat(chunker.chunk("abcdefg"))
            .extracting(DocumentChunkDraft::charStart, DocumentChunkDraft::charEnd)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, 4),
                org.assertj.core.groups.Tuple.tuple(3, 7)
            );
    }

    @ParameterizedTest(name = "[{index}] overlap={0}")
    @MethodSource("invalidOverlaps")
    @DisplayName("Overlap이 유효 범위를 벗어나면 Chunk 계산을 시작하지 않는다")
    void chunk_rejectsInvalidOverlap(int overlap) {
        properties.setOverlap(overlap);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> chunker.chunk("abcdef"));
    }

    private static Stream<Arguments> invalidOverlaps() {
        return Stream.of(
            Arguments.of(-1),
            Arguments.of(4),
            Arguments.of(5)
        );
    }
}
