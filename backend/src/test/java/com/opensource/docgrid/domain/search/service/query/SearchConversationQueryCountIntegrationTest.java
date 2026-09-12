package com.opensource.docgrid.domain.search.service.query;

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
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.entity.ResponseCitation;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.repository.ResponseCitationRepository;
import com.opensource.docgrid.domain.search.dto.response.SearchConversationResponse;
import com.opensource.docgrid.domain.search.entity.SearchConversation;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;
import com.opensource.docgrid.domain.search.repository.SearchConversationRepository;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * 대화 상세 조회가 Turn 수와 무관한 상수 SQL로 질문·검색 결과·RAG 답변·citation을 조립하는지 검증한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("대화 상세 SQL 개수 통합 테스트")
class SearchConversationQueryCountIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private SearchConversationRepository conversationRepository;
    @Autowired private SearchQueryRepository queryRepository;
    @Autowired private SearchResultRepository resultRepository;
    @Autowired private RagResponseRepository ragResponseRepository;
    @Autowired private ResponseCitationRepository citationRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentVersionRepository versionRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private SearchConversationQueryService service;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        service = new SearchConversationQueryService(
            conversationRepository, queryRepository, ragResponseRepository,
            resultRepository, citationRepository
        );
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @Test
    @DisplayName("완료 Turn이 1, 5, 10, 50개여도 SQL 수가 5회로 유지된다")
    void countQueriesByCompletedTurnCount() {
        User user = userRepository.save(User.builder()
            .email("conversation-query-probe-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .name("조회 계측 사용자")
            .status(UserStatus.ACTIVE)
            .build());
        Document document = documentRepository.save(Document.builder()
            .owner(user)
            .title("대화 상세 계측 문서")
            .documentType(DocumentType.TXT)
            .sourceType(DocumentSourceType.UPLOAD)
            .status(DocumentStatus.INDEXED)
            .visibility(VisibilityType.PRIVATE)
            .build());
        DocumentVersion version = versionRepository.save(DocumentVersion.builder()
            .document(document)
            .versionNo(1)
            .titleSnapshot(document.getTitle())
            .status(DocumentVersionStatus.INDEXED)
            .createdBy(user)
            .build());

        List<MeasurementTarget> targets = List.of(
            createConversation(user, version, 1, 0),
            createConversation(user, version, 5, 100),
            createConversation(user, version, 10, 200),
            createConversation(user, version, 50, 1_000)
        );
        entityManager.flush();
        entityManager.clear();

        List<Long> statementCounts = new ArrayList<>();
        for (MeasurementTarget target : targets) {
            statistics.clear();
            SearchConversationResponse response = service.getConversation(target.conversationId(), user.getId());
            assertThat(response.turns()).hasSize(target.turnCount());
            assertThat(response.turns().get(0).response().results()).hasSize(2);
            assertThat(response.turns().get(0).response().ragStatus()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(response.turns().get(0).response().citations()).hasSize(2);
            statementCounts.add(statistics.getPrepareStatementCount());
            entityManager.clear();
        }

        System.out.println("CONVERSATION_DETAIL_QUERY_COUNTS=" + statementCounts);
        assertThat(statementCounts).containsExactly(5L, 5L, 5L, 5L);
    }

    private MeasurementTarget createConversation(User user, DocumentVersion version, int turnCount, int chunkOffset) {
        SearchConversation conversation = conversationRepository.save(SearchConversation.builder()
            .user(user)
            .title(turnCount + "개 Turn 대화")
            .lastMessageAt(LocalDateTime.now())
            .build());

        for (int turn = 1; turn <= turnCount; turn++) {
            SearchQuery query = queryRepository.save(SearchQuery.builder()
                .user(user)
                .conversation(conversation)
                .queryText("질문 " + turn)
                .searchType(SearchType.VECTOR)
                .topK(2)
                .status(ResultStatus.SUCCESS)
                .build());
            RagResponse response = ragResponseRepository.save(RagResponse.builder()
                .query(query)
                .answerText("답변 " + turn)
                .status(ResultStatus.SUCCESS)
                .build());

            for (int rank = 1; rank <= 2; rank++) {
                DocumentChunk chunk = chunkRepository.save(DocumentChunk.builder()
                    .documentVersion(version)
                    .chunkIndex(chunkOffset + turn * 10 + rank)
                    .chunkText("질문 " + turn + "의 근거 " + rank)
                    .tokenCount(5)
                    .charStart(0)
                    .charEnd(10)
                    .pageNo(rank)
                    .contentHash(UUID.randomUUID().toString())
                    .build());
                SearchResult result = resultRepository.save(SearchResult.builder()
                    .query(query)
                    .chunk(chunk)
                    .rankNo(rank)
                    .similarityScore(new BigDecimal("0.900000"))
                    .finalScore(new BigDecimal("0.900000"))
                    .build());
                citationRepository.save(ResponseCitation.builder()
                    .response(response)
                    .chunk(chunk)
                    .searchResult(result)
                    .citationOrder(rank)
                    .citationLabel("[" + rank + "]")
                    .quotedText(chunk.getChunkText())
                    .pageNo(rank)
                    .relevanceScore(result.getSimilarityScore())
                    .build());
            }
        }
        return new MeasurementTarget(conversation.getId(), turnCount);
    }

    /** 계측할 대화 ID와 기대 Turn 수를 함께 보관한다. */
    private record MeasurementTarget(Long conversationId, int turnCount) {
    }
}
