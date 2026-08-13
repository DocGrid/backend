package com.opensource.docgrid.domain.document.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 문서 Chunk 크기와 Overlap 설정의 기본값 및 시작 단계 제약을 검증한다.
 *
 * <p>Chunk 시작 위치가 앞으로 이동하지 않는 조합을 Bean Validation이 차단하는지 확인한다.
 */
@DisplayName("DocumentChunkingProperties 테스트")
class DocumentChunkingPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("기본 Chunk 크기는 1000이고 Overlap은 200이다")
    void defaults_areValid() {
        DocumentChunkingProperties properties = new DocumentChunkingProperties();

        assertThat(properties.getChunkSize()).isEqualTo(1000);
        assertThat(properties.getOverlap()).isEqualTo(200);
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("Chunk 크기가 0 이하이면 유효하지 않다")
    void chunkSize_isInvalid_when_notPositive() {
        DocumentChunkingProperties properties = new DocumentChunkingProperties();
        properties.setOverlap(0);
        properties.setChunkSize(0);

        Set<ConstraintViolation<DocumentChunkingProperties>> zeroViolations = validator.validate(properties);

        properties.setChunkSize(-1);
        Set<ConstraintViolation<DocumentChunkingProperties>> negativeViolations = validator.validate(properties);

        assertThat(zeroViolations).isNotEmpty();
        assertThat(negativeViolations).isNotEmpty();
    }

    @Test
    @DisplayName("Overlap은 0 이상이고 Chunk 크기보다 작아야 한다")
    void overlap_isValid_onlyWithinChunkBoundary() {
        DocumentChunkingProperties properties = new DocumentChunkingProperties();
        properties.setChunkSize(10);

        properties.setOverlap(0);
        assertThat(validator.validate(properties)).isEmpty();

        properties.setOverlap(-1);
        assertThat(validator.validate(properties)).isNotEmpty();

        properties.setOverlap(10);
        assertThat(validator.validate(properties)).isNotEmpty();

        properties.setOverlap(11);
        assertThat(validator.validate(properties)).isNotEmpty();
    }
}
