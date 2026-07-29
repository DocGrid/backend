package com.opensource.docgrid.domain.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * 문서 텍스트를 고정 크기 Chunk로 나눌 때 사용하는 크기와 중첩 범위를 제공한다.
 *
 * <p>{@code document.chunking} 설정을 바인딩하고 애플리케이션 시작 시 Chunk 크기보다 작은
 * 0 이상의 Overlap만 허용해 무한 반복이나 잘못된 범위 계산을 방지한다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "document.chunking")
public class DocumentChunkingProperties {

    @Positive
    private int chunkSize = 1000;

    private int overlap = 200;

    /**
     * 다음 Chunk 시작 위치가 반드시 앞으로 이동하도록 Overlap 조합을 검증한다.
     */
    @AssertTrue(message = "Chunk Overlap은 0 이상이며 Chunk 크기보다 작아야 합니다.")
    public boolean isOverlapValid() {
        return overlap >= 0 && overlap < chunkSize;
    }
}
