package com.opensource.docgrid.global.common.response;

import java.util.List;

import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Spring Data의 내부 Page 직렬화 형식에 의존하지 않는 공통 페이지 응답 계약이다.
 *
 * <p>이미 DTO로 변환된 Content와 최소 Pagination Metadata만 외부에 노출하며 Entity나 정렬 구현
 * 세부사항은 포함하지 않는다.
 */
public record PageResponse<T>(
    @Schema(description = "현재 페이지 데이터")
    List<T> content,

    @Schema(description = "0부터 시작하는 현재 페이지 번호", example = "0")
    int page,

    @Schema(description = "요청한 페이지 크기", example = "20")
    int size,

    @Schema(description = "전체 데이터 수", example = "42")
    long totalElements,

    @Schema(description = "전체 페이지 수", example = "3")
    int totalPages,

    @Schema(description = "첫 페이지 여부", example = "true")
    boolean first,

    @Schema(description = "마지막 페이지 여부", example = "false")
    boolean last
) {

    /**
     * Entity Page의 Pagination Metadata와 변환 완료된 Content를 고정 응답으로 결합한다.
     */
    public static <T> PageResponse<T> from(Page<?> page, List<T> content) {
        return new PageResponse<>(
            List.copyOf(content),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.isFirst(),
            page.isLast()
        );
    }
}
