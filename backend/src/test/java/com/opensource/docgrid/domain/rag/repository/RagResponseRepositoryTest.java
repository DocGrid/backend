package com.opensource.docgrid.domain.rag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;

/**
 * RagJobTimeoutSweeper(#286)가 의존하는 두 쿼리를 실제 PostgreSQL Repository 계층에서
 * 검증한다. 특히 {@code forceFailIfProcessing()}의 "이미 끝난 job은 절대 덮어쓰지 않는다"는
 * 조건부 UPDATE 정합성은 이번 수정의 핵심 안전장치라 Mockito 단위 테스트로는 증명할 수 없고,
 * 실제 SQL이 실행되는 이 계층에서만 검증할 수 있다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("RagResponseRepository 테스트")
class RagResponseRepositoryTest {

    @Autowired
    private RagResponseRepository ragResponseRepository;

    @Autowired
    private SearchQueryRepository searchQueryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmbeddingModelRepository embeddingModelRepository;

    @Test
    @DisplayName("forceFailIfProcessing: PROCESSING인 job은 FAILED로 강제 종료되고 영향받은 행이 1건이다")
    void forceFailIfProcessing_processingJob_updatesToFailedAndReturnsOne() {
        RagResponse job = saveRagResponse(ResultStatus.PROCESSING);

        int updated = ragResponseRepository.forceFailIfProcessing(job.getId(), "fallback 답변", "타임아웃");

        assertThat(updated).isEqualTo(1);
        RagResponse reloaded = ragResponseRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ResultStatus.FAILED);
        assertThat(reloaded.getAnswerText()).isEqualTo("fallback 답변");
        assertThat(reloaded.getErrorMessage()).isEqualTo("타임아웃");
    }

    @Test
    @DisplayName("forceFailIfProcessing: 이미 SUCCESS로 끝난 job은 덮어쓰지 않고 영향받은 행이 0건이다")
    void forceFailIfProcessing_alreadySucceededJob_doesNotOverwriteAndReturnsZero() {
        RagResponse job = saveRagResponse(ResultStatus.PROCESSING);
        job.markSuccess("실제 답변", "qwen2.5:7b", 100, 20, 900);
        ragResponseRepository.saveAndFlush(job);

        // RagJobWorker가 이 순간 이미 SUCCESS로 커밋한 상황을 재현한다 — 스위퍼의 강제 종료는
        // 이 시점 이후 실행돼도 status 조건이 안 맞아 아무것도 바꾸면 안 된다.
        int updated = ragResponseRepository.forceFailIfProcessing(job.getId(), "fallback 답변", "타임아웃");

        assertThat(updated).isEqualTo(0);
        RagResponse reloaded = ragResponseRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(reloaded.getAnswerText()).isEqualTo("실제 답변");
    }

    @Test
    @DisplayName("findByStatusAndCreatedAtBefore: cutoff 이전에 생성된 PROCESSING만 찾고, 상태가 다른 job은 제외한다")
    void findByStatusAndCreatedAtBefore_filtersOnStatusAndCreatedAt() {
        LocalDateTime beforeAnyCreation = LocalDateTime.now();
        RagResponse processingJob = saveRagResponse(ResultStatus.PROCESSING);
        saveRagResponse(ResultStatus.SUCCESS);

        // cutoff가 두 job이 생성되기 전 시점이면(=아직 아무 job도 이 시간만큼 오래 기다리지 않음)
        // 아무것도 찾지 못해야 한다.
        assertThat(ragResponseRepository.findByStatusAndCreatedAtBefore(ResultStatus.PROCESSING, beforeAnyCreation))
            .isEmpty();

        LocalDateTime afterCreation = LocalDateTime.now();
        List<RagResponse> result =
            ragResponseRepository.findByStatusAndCreatedAtBefore(ResultStatus.PROCESSING, afterCreation);

        assertThat(result).extracting(RagResponse::getId).containsExactly(processingJob.getId());
    }

    private RagResponse saveRagResponse(ResultStatus status) {
        User user = userRepository.save(User.builder()
            .email("rag-repo-test-" + System.nanoTime() + "@test.local")
            .passwordHash("x")
            .name("RAG저장소테스트유저")
            .status(UserStatus.ACTIVE)
            .build());
        EmbeddingModel model = embeddingModelRepository.save(
            EmbeddingModelFixture.createModel("rag-repo-test-" + System.nanoTime(), false, false));
        SearchQuery query = searchQueryRepository.save(SearchQuery.builder()
            .user(user)
            .queryText("테스트 질문")
            .queryEmbeddingModel(model)
            .queryVector(new float[1024])
            .searchType(SearchType.VECTOR)
            .topK(5)
            .status(ResultStatus.SUCCESS)
            .build());
        return ragResponseRepository.save(RagResponse.builder()
            .query(query)
            .promptText("프롬프트")
            .status(status)
            .build());
    }
}
