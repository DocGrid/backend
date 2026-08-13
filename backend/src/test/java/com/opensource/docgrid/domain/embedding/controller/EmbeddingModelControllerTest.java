package com.opensource.docgrid.domain.embedding.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.embedding.dto.response.EmbeddingModelResponse;
import com.opensource.docgrid.domain.embedding.enums.DistanceMetric;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@WebMvcTest(EmbeddingModelController.class)
@DisplayName("EmbeddingModelController 테스트")
class EmbeddingModelControllerTest {

    private static final String ACTIVE_MODEL_URL = "/api/embedding-models/active";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmbeddingModelQueryService embeddingModelQueryService;

    // WebMvc slice에는 Entity metamodel이 없으므로 애플리케이션의 JPA Auditing 의존성만 대체한다.
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("인증된 사용자가 기본 임베딩 모델을 조회한다")
    void getActiveModel_returnsApiResponse() throws Exception {
        EmbeddingModelResponse response = new EmbeddingModelResponse(
            42L,
            EmbeddingProvider.MOCK,
            "mock-bge-m3",
            "v1",
            1024,
            DistanceMetric.COSINE
        );
        given(embeddingModelQueryService.getActiveModelResponse()).willReturn(response);

        mockMvc.perform(get(ACTIVE_MODEL_URL).with(user("docgrid-user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.id").value(42))
            .andExpect(jsonPath("$.data.provider").value("MOCK"))
            .andExpect(jsonPath("$.data.modelName").value("mock-bge-m3"))
            .andExpect(jsonPath("$.data.modelVersion").value("v1"))
            .andExpect(jsonPath("$.data.dimension").value(1024))
            .andExpect(jsonPath("$.data.distanceMetric").value("COSINE"))
            .andExpect(jsonPath("$.data.configJson").doesNotExist())
            .andExpect(jsonPath("$.data.vectorStorageStrategy").doesNotExist());
    }

    @Test
    @DisplayName("기본 모델이 없으면 전용 서버 설정 오류를 반환한다")
    void getActiveModel_returnsNotConfiguredError() throws Exception {
        given(embeddingModelQueryService.getActiveModelResponse())
            .willThrow(new DocGridException(ErrorCode.EMBEDDING_MODEL_NOT_CONFIGURED));

        mockMvc.perform(get(ACTIVE_MODEL_URL).with(user("docgrid-user")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.code").value("EMBEDDING-MODEL-001"));
    }

    @Test
    @DisplayName("기본 모델이 여러 개면 전용 서버 설정 오류를 반환한다")
    void getActiveModel_returnsMultipleModelsError() throws Exception {
        given(embeddingModelQueryService.getActiveModelResponse())
            .willThrow(new DocGridException(ErrorCode.MULTIPLE_ACTIVE_EMBEDDING_MODELS));

        mockMvc.perform(get(ACTIVE_MODEL_URL).with(user("docgrid-user")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.code").value("EMBEDDING-MODEL-002"));
    }
}
