package com.opensource.docgrid.domain.embedding.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 인덱싱 실패 요청의 Worker, Claim Token, 실패 유형과 진단 메시지 입력 경계를 검증한다.
 */
@DisplayName("FailDocumentIndexingRequest 테스트")
class FailDocumentIndexingRequestTest {

    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("모든 필수 값이 경계를 만족하면 유효하다")
    void validRequest_hasNoViolations() {
        FailDocumentIndexingRequest request = new FailDocumentIndexingRequest(
            7L,
            CLAIM_TOKEN,
            IndexingFailureType.EMBEDDING_PROVIDER_UNAVAILABLE,
            "Embedding provider request timed out"
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("양수가 아닌 Worker와 잘못된 Token은 거부한다")
    void ownershipFields_rejectInvalidValues() {
        FailDocumentIndexingRequest request = new FailDocumentIndexingRequest(
            0L,
            "not-a-canonical-uuid",
            IndexingFailureType.WORKER_INTERNAL_ERROR,
            "temporary failure"
        );

        assertThat(validator.validate(request))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("workerId", "claimToken");
    }

    @Test
    @DisplayName("실패 유형과 메시지는 필수이며 메시지는 2000자를 넘을 수 없다")
    void failureFields_rejectMissingOrOversizedValues() {
        FailDocumentIndexingRequest missingRequest = new FailDocumentIndexingRequest(
            7L,
            CLAIM_TOKEN,
            null,
            " "
        );
        FailDocumentIndexingRequest oversizedRequest = new FailDocumentIndexingRequest(
            7L,
            CLAIM_TOKEN,
            IndexingFailureType.STORAGE_UNAVAILABLE,
            "x".repeat(2001)
        );

        assertThat(validator.validate(missingRequest))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("failureType", "errorMessage");
        assertThat(validator.validate(oversizedRequest))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("errorMessage");
    }
}
