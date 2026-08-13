package com.opensource.docgrid.domain.embedding.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;

/**
 * Embedding Entity가 Vector 배열과 기본 활성 상태를 불변 값으로 보존하는지 검증한다.
 */
@DisplayName("Embedding 테스트")
class EmbeddingTest {

    @Test
    @DisplayName("생성에 사용한 Vector 배열을 변경해도 Entity 값은 유지된다")
    void constructor_copiesVector() {
        float[] vector = {0.1f, 0.2f};

        Embedding embedding = Embedding.builder()
            .vector(vector)
            .dimension(vector.length)
            .build();
        vector[0] = 9.9f;

        assertThat(embedding.getVector()).containsExactly(0.1f, 0.2f);
    }

    @Test
    @DisplayName("조회한 Vector 배열을 변경해도 Entity 값은 유지된다")
    void getter_returnsVectorCopy() {
        Embedding embedding = Embedding.builder()
            .vector(new float[]{0.1f, 0.2f})
            .dimension(2)
            .build();

        float[] exposedVector = embedding.getVector();
        exposedVector[0] = 9.9f;

        assertThat(embedding.getVector()).containsExactly(0.1f, 0.2f);
    }

    @Test
    @DisplayName("상태를 지정하지 않으면 ACTIVE로 생성된다")
    void constructor_defaultsToActiveStatus() {
        Embedding embedding = Embedding.builder()
            .vector(new float[]{0.1f})
            .dimension(1)
            .build();

        assertThat(embedding.getStatus()).isEqualTo(EmbeddingStatus.ACTIVE);
    }
}
