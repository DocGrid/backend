package com.opensource.docgrid.domain.embedding.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;

/**
 * Embedding Model 조회와 DB 제약을 실제 PostgreSQL Repository 계층에서 검증하는 테스트.
 *
 * <p>운영과 동일한 {@code BAAI/bge-m3} Seed를 기본 모델로 조회하고, 모델 식별자 Unique 제약과
 * active·searchable 단일 모델 제약이 적용되는지 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("EmbeddingModelRepository 테스트")
class EmbeddingModelRepositoryTest {

    private static final String SEEDED_MODEL_NAME = "BAAI/bge-m3";
    private static final String SEEDED_MODEL_VERSION = "1.0";

    @Autowired
    private EmbeddingModelRepository embeddingModelRepository;

    @Test
    @DisplayName("active이면서 searchable인 기본 bge-m3 모델을 조회한다")
    void findAllByIsActiveTrueAndIsSearchableTrue_returnsSeedModel() {
        List<EmbeddingModel> result = embeddingModelRepository.findAllByIsActiveTrueAndIsSearchableTrue();

        assertThat(result)
            .extracting(EmbeddingModel::getModelName)
            .containsExactly(SEEDED_MODEL_NAME);
    }

    @Test
    @DisplayName("active 또는 searchable이 false인 모델은 조회하지 않는다")
    void findAllByIsActiveTrueAndIsSearchableTrue_excludesUnavailableModels() {
        EmbeddingModel activeOnly = EmbeddingModelFixture.createModel("active-only", true, false);
        EmbeddingModel searchableOnly = EmbeddingModelFixture.createModel("searchable-only", false, true);
        EmbeddingModel unavailable = EmbeddingModelFixture.createModel("unavailable", false, false);
        embeddingModelRepository.saveAllAndFlush(List.of(activeOnly, searchableOnly, unavailable));

        List<EmbeddingModel> result = embeddingModelRepository.findAllByIsActiveTrueAndIsSearchableTrue();

        assertThat(result)
            .extracting(EmbeddingModel::getModelName)
            .doesNotContain("active-only", "searchable-only", "unavailable");
    }

    @Test
    @DisplayName("provider, 모델 이름, 버전 조합으로 존재 여부를 확인한다")
    void existsByProviderAndModelNameAndModelVersion_returnsTrue() {
        boolean exists = embeddingModelRepository.existsByProviderAndModelNameAndModelVersion(
            EmbeddingProvider.HUGGINGFACE,
            SEEDED_MODEL_NAME,
            SEEDED_MODEL_VERSION
        );

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("provider, 모델 이름, 버전이 중복되면 저장할 수 없다")
    void saveAndFlush_throws_when_identityIsDuplicated() {
        EmbeddingModel first = EmbeddingModelFixture.createModel("duplicate-model", false, false);
        EmbeddingModel duplicate = EmbeddingModelFixture.createModel("duplicate-model", false, false);
        embeddingModelRepository.saveAndFlush(first);

        assertThatThrownBy(() -> embeddingModelRepository.saveAndFlush(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("active이면서 searchable인 모델을 두 개 저장할 수 없다")
    void saveAndFlush_throws_when_multipleModelsAreActiveAndSearchable() {
        EmbeddingModel duplicateActiveModel =
            EmbeddingModelFixture.createModel("second-active-searchable", true, true);

        assertThatThrownBy(() -> embeddingModelRepository.saveAndFlush(duplicateActiveModel))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
