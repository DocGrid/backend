package com.opensource.docgrid.domain.embedding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * 문서 Chunk를 Embedding Server에 전달할 때 사용하는 Batch 크기 경계를 제공한다.
 *
 * <p>{@code embedding.document} 설정을 바인딩하고 요청당 Chunk 개수·문자·Token 예산을 제공한다.
 * 기본 Batch 4와 문자 4,000·Token 900 예산은 실제 PDF Chunk 분포와 Batch 실측으로 정한
 * 안전 상한이며, Query Embedding의 단건 호출 설정에는 관여하지 않는다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "embedding.document")
public class EmbeddingBatchProperties {

    @Min(1)
    @Max(64)
    private int batchSize = 4;

    @Min(1)
    private int maxCodePoints = 4_000;

    @Min(1)
    private int maxEstimatedTokens = 900;
}
