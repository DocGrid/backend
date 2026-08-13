package com.opensource.docgrid.domain.dashboard.event;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.embedding.event.EmbeddingJobStatusChangedEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingJobStatusChangedEventListener 단위 테스트")
class EmbeddingJobStatusChangedEventListenerTest {

    @InjectMocks private EmbeddingJobStatusChangedEventListener listener;

    @Mock private DashboardUpdateFlag dashboardUpdateFlag;

    @Test
    @DisplayName("정상 케이스: 이벤트를 받으면 무거운 작업 없이 플래그만 세운다")
    void onEmbeddingJobStatusChanged_marksFlagDirty() {
        // Given
        EmbeddingJobStatusChangedEvent event = new EmbeddingJobStatusChangedEvent(42L);

        // When
        listener.onEmbeddingJobStatusChanged(event);

        // Then
        then(dashboardUpdateFlag).should().markDirty();
        then(dashboardUpdateFlag).shouldHaveNoMoreInteractions();
    }
}
