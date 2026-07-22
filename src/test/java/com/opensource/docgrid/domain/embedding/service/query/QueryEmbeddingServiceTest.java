package com.opensource.docgrid.domain.embedding.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.opensource.docgrid.domain.embedding.dto.EmbedResult;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedServerResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("QueryEmbeddingService 단위 테스트")
class QueryEmbeddingServiceTest {

    @Mock private EmbeddingModelQueryService embeddingModelQueryService;
    @Mock private RestClient restClient;
    @Mock(answer = Answers.RETURNS_SELF) private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private QueryEmbeddingService queryEmbeddingService;

    @BeforeEach
    void setUp() {
        queryEmbeddingService = new QueryEmbeddingService(embeddingModelQueryService, restClient);
        doReturn(requestBodyUriSpec).when(restClient).post();
        doReturn(responseSpec).when(requestBodyUriSpec).retrieve();
    }

    @Test
    @DisplayName("정상 케이스: 텍스트를 1024차원 벡터로 변환한다")
    void embed_success() {
        EmbeddingModel model = EmbeddingModelFixture.createDefaultModel();
        float[] vector = new float[EmbeddingModelFixture.DIMENSION];
        EmbedServerResponse serverResponse = new EmbedServerResponse(vector, EmbeddingModelFixture.DIMENSION);

        given(embeddingModelQueryService.getActiveModel()).willReturn(model);
        given(responseSpec.body(EmbedServerResponse.class)).willReturn(serverResponse);

        EmbedResult result = queryEmbeddingService.embed("검색어");

        assertThat(result.vector()).hasSize(EmbeddingModelFixture.DIMENSION);
        assertThat(result.model()).isEqualTo(model);
    }

    @Test
    @DisplayName("차원 불일치: 응답 차원이 모델 차원과 다르면 EMBEDDING_DIMENSION_MISMATCH 예외가 발생한다")
    void embed_dimensionMismatch_throwsException() {
        EmbeddingModel model = EmbeddingModelFixture.createDefaultModel();
        EmbedServerResponse serverResponse = new EmbedServerResponse(new float[768], 768);

        given(embeddingModelQueryService.getActiveModel()).willReturn(model);
        given(responseSpec.body(EmbedServerResponse.class)).willReturn(serverResponse);

        assertThatThrownBy(() -> queryEmbeddingService.embed("검색어"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
    }

    @Test
    @DisplayName("서버 장애: RestClientException 발생 시 EMBEDDING_SERVER_UNAVAILABLE 예외가 발생한다")
    void embed_serverUnavailable_throwsException() {
        EmbeddingModel model = EmbeddingModelFixture.createDefaultModel();

        given(embeddingModelQueryService.getActiveModel()).willReturn(model);
        given(responseSpec.body(EmbedServerResponse.class))
            .willThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> queryEmbeddingService.embed("검색어"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_SERVER_UNAVAILABLE);
    }
}
