package com.opensource.docgrid.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenBlacklistService 단위 테스트")
class TokenBlacklistServiceTest {

    @InjectMocks
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("blacklist 호출 시 jti를 남은 만료 시간만큼 TTL로 저장한다")
    void blacklist_setsKeyWithTtl() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        tokenBlacklistService.blacklist("test-jti", 600L);

        then(valueOperations).should().set("auth:blacklist:test-jti", "1", Duration.ofSeconds(600));
    }

    @Test
    @DisplayName("블랙리스트에 등록된 jti는 isBlacklisted가 true를 반환한다")
    void isBlacklisted_returnsTrue_whenKeyExists() {
        given(redisTemplate.hasKey("auth:blacklist:test-jti")).willReturn(true);

        assertThat(tokenBlacklistService.isBlacklisted("test-jti")).isTrue();
    }

    @Test
    @DisplayName("블랙리스트에 없는 jti는 isBlacklisted가 false를 반환한다")
    void isBlacklisted_returnsFalse_whenKeyMissing() {
        given(redisTemplate.hasKey("auth:blacklist:test-jti")).willReturn(false);

        assertThat(tokenBlacklistService.isBlacklisted("test-jti")).isFalse();
    }
}
