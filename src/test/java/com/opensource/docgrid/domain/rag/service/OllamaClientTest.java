package com.opensource.docgrid.domain.rag.service;

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

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.dto.response.OllamaGenerateResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OllamaClient 단위 테스트")
class OllamaClientTest {

    @Mock private RestClient restClient;
    @Mock(answer = Answers.RETURNS_SELF) private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private OllamaClient ollamaClient;

    @BeforeEach
    void setUp() {
        ollamaClient = new OllamaClient("qwen2.5:3b", restClient);
        doReturn(requestBodyUriSpec).when(restClient).post();
        doReturn(responseSpec).when(requestBodyUriSpec).retrieve();
    }

    @Test
    @DisplayName("정상 케이스: 프롬프트를 전달하면 답변 텍스트와 토큰 수를 반환한다")
    void generate_success() {
        OllamaGenerateResponse serverResponse = new OllamaGenerateResponse(
            "연차는 입사 1년 기준 15일 부여됩니다.", true, 120, 45
        );
        given(responseSpec.body(OllamaGenerateResponse.class)).willReturn(serverResponse);

        OllamaGenerateResult result = ollamaClient.generate("질문: 연차 규정 알려줘");

        assertThat(result.answerText()).isEqualTo("연차는 입사 1년 기준 15일 부여됩니다.");
        assertThat(result.inputTokenCount()).isEqualTo(120);
        assertThat(result.outputTokenCount()).isEqualTo(45);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("서버 장애: RestClientException 발생 시 RAG_SERVICE_UNAVAILABLE 예외가 발생한다")
    void generate_serverUnavailable_throwsException() {
        given(responseSpec.body(OllamaGenerateResponse.class))
            .willThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> ollamaClient.generate("질문"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RAG_SERVICE_UNAVAILABLE);
    }
}
