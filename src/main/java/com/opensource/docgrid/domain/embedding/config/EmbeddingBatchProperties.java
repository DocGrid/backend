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
 * <p>{@code embedding.document} 설정을 바인딩하고 애플리케이션 시작 시 서버 계약과 같은
 * 1~64 범위만 허용한다. 기본값 32는 실제 BGE-M3 CPU Benchmark의 처리량·p95·RSS 균형점이며,
 * Query Embedding의 단건 호출 설정에는 관여하지 않는다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "embedding.document")
public class EmbeddingBatchProperties {

    @Min(1)
    @Max(64)
    private int batchSize = 32;
}
