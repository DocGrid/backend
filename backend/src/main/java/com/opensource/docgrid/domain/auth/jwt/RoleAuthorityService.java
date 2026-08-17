package com.opensource.docgrid.domain.auth.jwt;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 인가(hasRole) 판단에 쓰는 사용자 role을 JWT가 아니라 DB에서 매 요청 조회한다.
 *
 * <p>JWT에 role을 박제하면 관리자가 role을 부여/회수해도 재로그인 전까지 반영되지 않는다.
 * DB 조회 부하를 줄이기 위해 Redis에 짧은 TTL로 캐싱하고, role 변경 시 즉시 무효화한다.
 *
 * <p>이 서비스는 인증 필터(모든 요청)의 critical path에 있으므로, {@code TokenBlacklistService}와
 * 동일하게 Redis 장애 시 예외를 전파하지 않고 DB 조회로 폴백한다 — Redis가 죽었다고 전체 API가
 * 막히면 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleAuthorityService {

    private static final String KEY_PREFIX = "auth:roles:";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final UserRoleRepository userRoleRepository;

    public List<String> getRoles(Long userId) {
        String cached = readCache(userId);
        if (cached != null) {
            return cached.isBlank() ? List.of() : Arrays.asList(cached.split(","));
        }

        List<String> roles = userRoleRepository.findRoleCodesByUserId(userId);
        writeCache(userId, roles);
        return roles;
    }

    public void invalidate(Long userId) {
        try {
            redisTemplate.delete(KEY_PREFIX + userId);
        } catch (Exception e) {
            log.error("Redis role 캐시 무효화 실패, userId={}: {}", userId, e.getMessage());
        }
    }

    private String readCache(Long userId) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        } catch (Exception e) {
            log.error("Redis role 캐시 조회 실패, DB로 폴백합니다. userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private void writeCache(Long userId, List<String> roles) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + userId, String.join(",", roles), TTL);
        } catch (Exception e) {
            log.error("Redis role 캐시 저장 실패, userId={}: {}", userId, e.getMessage());
        }
    }
}
