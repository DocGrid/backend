package com.opensource.docgrid.domain.search.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.entity.SearchConversation;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * 검색 결과를 후보 DTO로 변환할 때 필요한 청크·버전·문서 조회 계약을 검증한다.
 *
 * <p>저장소 조회가 전체 단일 연관관계를 한 번에 초기화해 N+1 조회를 막고, 조회 세션이
 * 닫힌 뒤에도 {@link VectorSearchCandidate#from(SearchResult)} 변환이 가능한지를 실제 DB로 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("SearchResultRepository 후보 DTO fetch 테스트")
class SearchResultRepositoryFetchTest {

    private static final int RESULT_COUNT = 5;

    @Autowired private SearchResultRepository searchResultRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private Long queryId;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        queryId = saveSearchResults();
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
    }

    @Test
    @DisplayName("서로 다른 문서 5건의 후보 DTO를 검색 순서대로 SELECT 1회에 변환한다")
    void findByQueryIdOrderByRankNo_fetchesCandidateGraphInSingleSelect() {
        // 1. 검색 결과와 DTO 변환에 필요한 단일 연관관계를 저장소에서 함께 조회한다.
        List<SearchResult> results = searchResultRepository.findByQuery_IdOrderByRankNo(queryId);

        // 2. 변환 중 지연 조회가 추가되지 않고 검색 순서와 문서 정보가 유지돼야 한다.
        List<VectorSearchCandidate> candidates = results.stream()
            .map(VectorSearchCandidate::from)
            .toList();

        assertThat(candidates).extracting(VectorSearchCandidate::documentTitle)
            .containsExactly("문서 1", "문서 2", "문서 3", "문서 4", "문서 5");
        assertThat(candidates).extracting(VectorSearchCandidate::chunkText)
            .containsExactly("청크 1", "청크 2", "청크 3", "청크 4", "청크 5");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("조회 세션에서 분리된 검색 결과도 후보 DTO로 변환할 수 있다")
    void findByQueryIdOrderByRankNo_supportsCandidateConversionAfterDetach() {
        // 1. 저장소가 반환한 객체를 세션에서 분리해 비동기 트랜잭션 경계 밖 사용을 재현한다.
        List<SearchResult> results = searchResultRepository.findByQuery_IdOrderByRankNo(queryId);
        entityManager.clear();

        // 2. 필요한 연관관계가 이미 초기화돼 추가 조회나 LazyInitializationException 없이 변환된다.
        List<VectorSearchCandidate> candidates = results.stream()
            .map(VectorSearchCandidate::from)
            .toList();

        assertThat(candidates).hasSize(RESULT_COUNT);
        assertThat(candidates.get(0).documentTitle()).isEqualTo("문서 1");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    private Long saveSearchResults() {
        User user = User.builder()
            .email("search-result-fetch-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("검색 결과 fetch 테스트 사용자")
            .status(UserStatus.ACTIVE)
            .build();
        entityManager.persist(user);

        SearchConversation conversation = SearchConversation.builder()
            .user(user)
            .title("검색 결과 fetch 테스트")
            .lastMessageAt(LocalDateTime.now())
            .build();
        entityManager.persist(conversation);

        SearchQuery query = SearchQuery.builder()
            .user(user)
            .conversation(conversation)
            .queryText("문서 찾아줘")
            .searchType(SearchType.VECTOR)
            .topK(RESULT_COUNT)
            .status(ResultStatus.SUCCESS)
            .build();
        entityManager.persist(query);

        List<SearchResult> results = new ArrayList<>();
        for (int index = 1; index <= RESULT_COUNT; index++) {
            Document document = Document.builder()
                .owner(user)
                .title("문서 " + index)
                .documentType(DocumentType.TXT)
                .sourceType(DocumentSourceType.UPLOAD)
                .status(DocumentStatus.INDEXED)
                .visibility(VisibilityType.PRIVATE)
                .build();
            entityManager.persist(document);

            DocumentVersion version = DocumentVersion.builder()
                .document(document)
                .versionNo(1)
                .titleSnapshot(document.getTitle())
                .status(DocumentVersionStatus.INDEXED)
                .createdBy(user)
                .build();
            entityManager.persist(version);

            DocumentChunk chunk = DocumentChunk.builder()
                .documentVersion(version)
                .chunkIndex(0)
                .chunkText("청크 " + index)
                .tokenCount(2)
                .charStart(0)
                .charEnd(3)
                .pageNo(index)
                .build();
            entityManager.persist(chunk);

            results.add(SearchResult.builder()
                .query(query)
                .chunk(chunk)
                .rankNo(index)
                .similarityScore(BigDecimal.ONE.subtract(BigDecimal.valueOf(index, 2)))
                .finalScore(BigDecimal.ONE.subtract(BigDecimal.valueOf(index, 2)))
                .matchedText(chunk.getChunkText())
                .build());
        }
        results.forEach(entityManager::persist);
        return query.getId();
    }
}
