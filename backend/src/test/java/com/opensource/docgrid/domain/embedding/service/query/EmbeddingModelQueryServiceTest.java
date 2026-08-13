package com.opensource.docgrid.domain.embedding.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.embedding.converter.EmbeddingModelConverter;
import com.opensource.docgrid.domain.embedding.dto.response.EmbeddingModelResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.DistanceMetric;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingModelQueryService 단위 테스트")
class EmbeddingModelQueryServiceTest {

    @InjectMocks
    private EmbeddingModelQueryService embeddingModelQueryService;

    @Mock
    private EmbeddingModelRepository embeddingModelRepository;

    @Mock
    private EmbeddingModelConverter embeddingModelConverter;

    @Test
    @DisplayName("사용 가능한 모델이 하나면 해당 Entity를 반환한다")
    void getActiveModel_returnsModel_when_exactlyOneExists() {
        EmbeddingModel expected = EmbeddingModelFixture.createDefaultModel();
        given(embeddingModelRepository.findAllByIsActiveTrueAndIsSearchableTrue())
            .willReturn(List.of(expected));

        EmbeddingModel result = embeddingModelQueryService.getActiveModel();

        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("사용 가능한 모델이 없으면 설정 오류가 발생한다")
    void getActiveModel_throws_when_noModelExists() {
        given(embeddingModelRepository.findAllByIsActiveTrueAndIsSearchableTrue())
            .willReturn(List.of());

        assertThatThrownBy(() -> embeddingModelQueryService.getActiveModel())
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_MODEL_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("사용 가능한 모델이 여러 개면 설정 오류가 발생한다")
    void getActiveModel_throws_when_multipleModelsExist() {
        EmbeddingModel first = EmbeddingModelFixture.createDefaultModel();
        EmbeddingModel second = EmbeddingModelFixture.createModel("another-model", true, true);
        given(embeddingModelRepository.findAllByIsActiveTrueAndIsSearchableTrue())
            .willReturn(List.of(first, second));

        assertThatThrownBy(() -> embeddingModelQueryService.getActiveModel())
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MULTIPLE_ACTIVE_EMBEDDING_MODELS);
    }

    @Test
    @DisplayName("외부 조회는 Entity를 Converter로 변환해 반환한다")
    void getActiveModelResponse_convertsEntityToResponse() {
        EmbeddingModel model = EmbeddingModelFixture.createDefaultModel();
        EmbeddingModelResponse expected = new EmbeddingModelResponse(
            null,
            EmbeddingProvider.MOCK,
            EmbeddingModelFixture.MODEL_NAME,
            EmbeddingModelFixture.MODEL_VERSION,
            EmbeddingModelFixture.DIMENSION,
            DistanceMetric.COSINE
        );
        given(embeddingModelRepository.findAllByIsActiveTrueAndIsSearchableTrue())
            .willReturn(List.of(model));
        given(embeddingModelConverter.toResponse(model)).willReturn(expected);

        EmbeddingModelResponse result = embeddingModelQueryService.getActiveModelResponse();

        assertThat(result).isEqualTo(expected);
        then(embeddingModelConverter).should().toResponse(model);
    }
}
