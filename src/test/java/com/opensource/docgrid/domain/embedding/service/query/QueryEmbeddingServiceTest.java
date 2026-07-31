package com.opensource.docgrid.domain.embedding.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.dto.EmbedResult;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueryEmbeddingService 단위 테스트")
class QueryEmbeddingServiceTest {

    @Mock private EmbeddingModelQueryService embeddingModelQueryService;
    @Mock private EmbeddingClient embeddingClient;
    @InjectMocks private QueryEmbeddingService queryEmbeddingService;

    @Test
    @DisplayName("정상 케이스: 텍스트를 1024차원 벡터로 변환한다")
    void embed_success() {
        EmbeddingModel model = EmbeddingModelFixture.createDefaultModel();
        float[] vector = new float[EmbeddingModelFixture.DIMENSION];

        given(embeddingModelQueryService.getActiveModel()).willReturn(model);
        given(embeddingClient.embed("검색어")).willReturn(vector);

        EmbedResult result = queryEmbeddingService.embed("검색어");

        assertThat(result.vector()).hasSize(EmbeddingModelFixture.DIMENSION);
        assertThat(result.model()).isEqualTo(model);
    }

    @Test
    @DisplayName("차원 불일치: 응답 차원이 모델 차원과 다르면 EMBEDDING_DIMENSION_MISMATCH 예외가 발생한다")
    void embed_dimensionMismatch_throwsException() {
        EmbeddingModel model = EmbeddingModelFixture.createDefaultModel();

        given(embeddingModelQueryService.getActiveModel()).willReturn(model);
        given(embeddingClient.embed("검색어")).willReturn(new float[768]);

        assertThatThrownBy(() -> queryEmbeddingService.embed("검색어"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
    }

    @Test
    @DisplayName("빈 응답: Vector가 null이면 EMBEDDING_DIMENSION_MISMATCH 예외가 발생한다")
    void embed_nullVector_throwsException() {
        EmbeddingModel model = EmbeddingModelFixture.createDefaultModel();

        given(embeddingModelQueryService.getActiveModel()).willReturn(model);
        given(embeddingClient.embed("검색어")).willReturn(null);

        assertThatThrownBy(() -> queryEmbeddingService.embed("검색어"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
    }
}
