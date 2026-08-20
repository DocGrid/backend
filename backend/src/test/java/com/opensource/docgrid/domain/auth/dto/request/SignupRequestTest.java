package com.opensource.docgrid.domain.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

@DisplayName("SignupRequest 테스트")
class SignupRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("비밀번호가 12자 이상 64자 이하이면 유효하다")
    void validRequest_hasNoViolations() {
        SignupRequest request = new SignupRequest("test@docgrid.com", "password1234", "테스트유저", 1L);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("비밀번호가 12자 미만이면 거부한다")
    void password_rejectsWhenTooShort() {
        SignupRequest request = new SignupRequest("test@docgrid.com", "short1", "테스트유저", 1L);

        assertThat(validator.validate(request))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("password");
    }

    @Test
    @DisplayName("비밀번호가 64자를 초과하면 거부한다")
    void password_rejectsWhenTooLong() {
        SignupRequest request = new SignupRequest("test@docgrid.com", "a".repeat(65), "테스트유저", 1L);

        assertThat(validator.validate(request))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("password");
    }
}
