package com.opensource.docgrid.domain.rag.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.fixture.SearchQueryFixture;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagResponseCommandService 단위 테스트")
class RagResponseCommandServiceTest {

    @InjectMocks
    private RagResponseCommandService ragResponseCommandService;

    @Mock
    private RagResponseRepository ragResponseRepository;

    @Test
    @DisplayName("createSuccess: SUCCESS 상태로 답변/모델명/토큰수/latency를 저장한다")
    void createSuccess_savesWithSuccessStatus() {
        SearchQuery query = SearchQueryFixture.createProcessing();
        OllamaGenerateResult result = new OllamaGenerateResult(
            "qwen2.5:3b", "연차는 입사 1년 기준 15일 부여됩니다.", 120, 45, 1800
        );
        given(ragResponseRepository.save(any(RagResponse.class))).willAnswer(i -> i.getArgument(0));

        ragResponseCommandService.createSuccess(query, "조립된 프롬프트", result);

        ArgumentCaptor<RagResponse> captor = ArgumentCaptor.forClass(RagResponse.class);
        then(ragResponseRepository).should(times(1)).save(captor.capture());

        RagResponse saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(saved.getAnswerText()).isEqualTo("연차는 입사 1년 기준 15일 부여됩니다.");
        assertThat(saved.getLlmProvider()).isEqualTo("Ollama");
        assertThat(saved.getLlmModelName()).isEqualTo("qwen2.5:3b");
        assertThat(saved.getPromptText()).isEqualTo("조립된 프롬프트");
        assertThat(saved.getInputTokenCount()).isEqualTo(120);
        assertThat(saved.getOutputTokenCount()).isEqualTo(45);
        assertThat(saved.getLatencyMs()).isEqualTo(1800);
    }

    @Test
    @DisplayName("createFailed: FAILED 상태로 고정 답변 문구와 실패 사유를 저장한다")
    void createFailed_savesWithFailedStatus() {
        SearchQuery query = SearchQueryFixture.createProcessing();
        given(ragResponseRepository.save(any(RagResponse.class))).willAnswer(i -> i.getArgument(0));

        ragResponseCommandService.createFailed(query, "조립된 프롬프트", "Ollama 서버 연결 실패");

        ArgumentCaptor<RagResponse> captor = ArgumentCaptor.forClass(RagResponse.class);
        then(ragResponseRepository).should(times(1)).save(captor.capture());

        RagResponse saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ResultStatus.FAILED);
        assertThat(saved.getAnswerText()).isEqualTo("답변 생성에 실패했습니다.");
        assertThat(saved.getErrorMessage()).isEqualTo("Ollama 서버 연결 실패");
        assertThat(saved.getLlmProvider()).isEqualTo("Ollama");
    }

    @Test
    @DisplayName("createNoContext: SUCCESS 상태로 고정 안내 문구를 저장한다")
    void createNoContext_savesWithFixedAnswer() {
        SearchQuery query = SearchQueryFixture.createProcessing();
        given(ragResponseRepository.save(any(RagResponse.class))).willAnswer(i -> i.getArgument(0));

        ragResponseCommandService.createNoContext(query);

        ArgumentCaptor<RagResponse> captor = ArgumentCaptor.forClass(RagResponse.class);
        then(ragResponseRepository).should(times(1)).save(captor.capture());

        RagResponse saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(saved.getAnswerText()).isEqualTo("관련 문서를 찾지 못했습니다.");
        assertThat(saved.getLlmProvider()).isNull();
        assertThat(saved.getLlmModelName()).isNull();
        assertThat(saved.getPromptText()).isNull();
        assertThat(saved.getInputTokenCount()).isNull();
        assertThat(saved.getOutputTokenCount()).isNull();
        assertThat(saved.getLatencyMs()).isNull();
        assertThat(saved.getErrorMessage()).isNull();
    }
}
