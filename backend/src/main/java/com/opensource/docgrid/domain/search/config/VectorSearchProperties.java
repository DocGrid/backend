package com.opensource.docgrid.domain.search.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 벡터 검색의 관련성, 문서 다양성과 후보 풀 정책을 제공한다.
 *
 * <p>{@code search.vector} 설정을 바인딩하고 애플리케이션 시작 시 각 정책의 안전한 범위를 검증한다.
 * 이 설정은 사용자 요청이 아니라 서버 정책으로 적용되어 검색 응답, 저장 결과와 RAG 문맥이
 * 동일한 관련성과 문서 다양성 기준을 사용하도록 제한한다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "search.vector")
public class VectorSearchProperties {

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private BigDecimal minSimilarity = new BigDecimal("0.30");

    @Min(1)
    @Max(20)
    private int maxChunksPerDocument = 2;

    @Min(1)
    @Max(10)
    private int candidatePoolMultiplier = 4;
}
