package com.opensource.docgrid.domain.embedding.client;

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

import com.opensource.docgrid.domain.embedding.dto.response.EmbedServerResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * EmbeddingClient의 HTTP 응답 전달과 외부 장애 변환 경계를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmbeddingClient 단위 테스트")
class EmbeddingClientTest {

    @Mock private RestClient restClient;
    @Mock(answer = Answers.RETURNS_SELF) private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private EmbeddingClient embeddingClient;

    @BeforeEach
    void setUp() {
        embeddingClient = new EmbeddingClient(restClient);
        doReturn(requestBodyUriSpec).when(restClient).post();
        doReturn(responseSpec).when(requestBodyUriSpec).retrieve();
    }

    @Test
    @DisplayName("정상 응답: 외부 서버의 Vector를 그대로 반환한다")
    void embed_success() {
        float[] vector = new float[]{0.1f, 0.2f};
        given(responseSpec.body(EmbedServerResponse.class))
            .willReturn(new EmbedServerResponse(vector));

        float[] result = embeddingClient.embed("검색어");

        assertThat(result).containsExactly(vector);
    }

    @Test
    @DisplayName("빈 응답: 외부 서버 응답이 null이면 null을 반환한다")
    void embed_returnsNull_whenResponseIsNull() {
        given(responseSpec.body(EmbedServerResponse.class)).willReturn(null);

        float[] result = embeddingClient.embed("검색어");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("서버 장애: RestClientException을 서비스 사용 불가 오류로 변환한다")
    void embed_throws_whenServerUnavailable() {
        given(responseSpec.body(EmbedServerResponse.class))
            .willThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> embeddingClient.embed("검색어"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_SERVER_UNAVAILABLE);
    }
}
