package com.opensource.docgrid.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("DocumentRepository.findByIdWithCurrentVersion 테스트")
class DocumentRepositoryFetchTest {

    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("정상 케이스: currentVersion이 JOIN FETCH로 즉시 로딩되어 영속성 컨텍스트 초기화 후에도 지연 로딩 없이 접근 가능하다")
    void findByIdWithCurrentVersion_eagerlyFetchesCurrentVersion() {
        // Given
        User owner = saveOwner();
        Document document = saveDocument(owner, "JOIN FETCH 테스트 문서", DocumentStatus.INDEXED);
        DocumentVersion version = saveVersion(document, owner, 1, DocumentVersionStatus.INDEXED);
        document.updateCurrentVersion(version);
        flushAndClear();

        // When — #120에서 OSIV가 꺼진 /mcp 경로를 흉내내, JOIN FETCH가 없었다면
        // 트랜잭션 밖에서 LazyInitializationException이 났을 지점을 실제 쿼리로 검증한다.
        Document found = documentRepository.findByIdWithCurrentVersion(document.getId())
                .orElseThrow();

        // Then
        assertThat(Hibernate.isInitialized(found.getCurrentVersion())).isTrue();
        assertThat(found.getCurrentVersion().getVersionNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("정상 케이스: currentVersion이 없는 문서도 예외 없이 조회된다")
    void findByIdWithCurrentVersion_returnsDocument_whenCurrentVersionMissing() {
        // Given
        User owner = saveOwner();
        Document document = saveDocument(owner, "버전 없는 문서", DocumentStatus.DRAFT);
        flushAndClear();

        // When
        Optional<Document> found = documentRepository.findByIdWithCurrentVersion(document.getId());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getCurrentVersion()).isNull();
    }

    private User saveOwner() {
        return userRepository.save(
            User.builder()
                .email("fetch-test-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("JOIN FETCH 테스트 사용자")
                .status(UserStatus.ACTIVE)
                .build()
        );
    }

    private Document saveDocument(User owner, String title, DocumentStatus status) {
        return documentRepository.save(
            Document.builder()
                .owner(owner)
                .title(title)
                .documentType(DocumentType.TXT)
                .sourceType(DocumentSourceType.UPLOAD)
                .status(status)
                .visibility(VisibilityType.PRIVATE)
                .build()
        );
    }

    private DocumentVersion saveVersion(
        Document document,
        User owner,
        int versionNo,
        DocumentVersionStatus status
    ) {
        return documentVersionRepository.save(
            DocumentVersion.builder()
                .document(document)
                .versionNo(versionNo)
                .titleSnapshot(document.getTitle())
                .status(status)
                .createdBy(owner)
                .build()
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
