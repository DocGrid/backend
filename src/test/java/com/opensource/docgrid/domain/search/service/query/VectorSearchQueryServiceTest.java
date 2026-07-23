package com.opensource.docgrid.domain.search.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.repository.VectorSearchRepository;
import com.opensource.docgrid.domain.search.repository.VectorSearchRow;

@ExtendWith(MockitoExtension.class)
@DisplayName("VectorSearchQueryService 단위 테스트")
class VectorSearchQueryServiceTest {

    @InjectMocks
    private VectorSearchQueryService vectorSearchQueryService;

    @Mock
    private VectorSearchRepository vectorSearchRepository;

    private static final float[] VECTOR = new float[1024];
    private static final Long MODEL_ID = 1L;

    @Test
    @DisplayName("permittedIds가 비어있으면 DB 조회 없이 빈 목록을 반환한다")
    void search_emptyPermittedIds_returnsEmptyWithoutQuery() {
        List<VectorSearchCandidate> result = vectorSearchQueryService.search(VECTOR, MODEL_ID, List.of(), 5);

        assertThat(result).isEmpty();
        then(vectorSearchRepository).should(never()).findTopK(anyString(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("정상 케이스: 쿼리 결과를 VectorSearchCandidate로 변환해 반환한다")
    void search_withResults_returnsMappedCandidates() {
        VectorSearchRow row = mockRow(1L, 2L, 3L, "청크 텍스트", 1, "문서 제목", 0.2);
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(row));

        List<VectorSearchCandidate> result = vectorSearchQueryService.search(
            VECTOR, MODEL_ID, List.of(3L), 5
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).documentId()).isEqualTo(3L);
        assertThat(result.get(0).chunkText()).isEqualTo("청크 텍스트");
        // similarityScore = 1 - 0.2 = 0.8
        assertThat(result.get(0).similarityScore().doubleValue()).isEqualTo(0.8, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("결과가 없으면 빈 목록을 반환한다")
    void search_noResults_returnsEmpty() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of());

        List<VectorSearchCandidate> result = vectorSearchQueryService.search(
            VECTOR, MODEL_ID, List.of(1L), 5
        );

        assertThat(result).isEmpty();
    }

    private VectorSearchRow mockRow(Long embeddingId, Long chunkId, Long documentId,
                                     String chunkText, Integer pageNo, String documentTitle, double distance) {
        return new VectorSearchRow() {
            public Long getEmbeddingId() { return embeddingId; }
            public Long getChunkId() { return chunkId; }
            public Long getDocumentId() { return documentId; }
            public String getChunkText() { return chunkText; }
            public Integer getPageNo() { return pageNo; }
            public String getDocumentTitle() { return documentTitle; }
            public Double getDistance() { return distance; }
        };
    }
}
