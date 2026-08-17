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
    @DisplayName("createPending: PROCESSING 상태로 프롬프트만 먼저 저장한다(답변 없음)")
    void createPending_savesWithProcessingStatus() {
        SearchQuery query = SearchQueryFixture.createProcessing();
        given(ragResponseRepository.save(any(RagResponse.class))).willAnswer(i -> i.getArgument(0));

        ragResponseCommandService.createPending(query, "조립된 프롬프트");

        ArgumentCaptor<RagResponse> captor = ArgumentCaptor.forClass(RagResponse.class);
        then(ragResponseRepository).should(times(1)).save(captor.capture());

        RagResponse saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ResultStatus.PROCESSING);
        assertThat(saved.getAnswerText()).isNull();
        assertThat(saved.getLlmProvider()).isEqualTo("Ollama");
        assertThat(saved.getPromptText()).isEqualTo("조립된 프롬프트");
    }

    @Test
    @DisplayName("completeSuccess: PROCESSING row를 SUCCESS로 채운다(dirty checking, save 재호출 없음)")
    void completeSuccess_fillsProcessingRowWithSuccessStatus() {
        RagResponse pending = RagResponse.builder().status(ResultStatus.PROCESSING).promptText("조립된 프롬프트").build();
        OllamaGenerateResult result = new OllamaGenerateResult(
            "qwen2.5:7b", "연차는 입사 1년 기준 15일 부여됩니다.", 120, 45, 1800
        );

        ragResponseCommandService.completeSuccess(pending, result);

        assertThat(pending.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(pending.getAnswerText()).isEqualTo("연차는 입사 1년 기준 15일 부여됩니다.");
        assertThat(pending.getLlmModelName()).isEqualTo("qwen2.5:7b");
        assertThat(pending.getInputTokenCount()).isEqualTo(120);
        assertThat(pending.getOutputTokenCount()).isEqualTo(45);
        assertThat(pending.getLatencyMs()).isEqualTo(1800);
        then(ragResponseRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("completeFailed: PROCESSING row를 FAILED로 채우되 답변에는 fallback 텍스트를 남긴다")
    void completeFailed_fillsProcessingRowWithFailedStatusAndFallbackText() {
        RagResponse pending = RagResponse.builder().status(ResultStatus.PROCESSING).promptText("조립된 프롬프트").build();

        ragResponseCommandService.completeFailed(pending, "extractive fallback 텍스트", "Ollama 서버 연결 실패");

        assertThat(pending.getStatus()).isEqualTo(ResultStatus.FAILED);
        assertThat(pending.getAnswerText()).isEqualTo("extractive fallback 텍스트");
        assertThat(pending.getErrorMessage()).isEqualTo("Ollama 서버 연결 실패");
        then(ragResponseRepository).shouldHaveNoInteractions();
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
