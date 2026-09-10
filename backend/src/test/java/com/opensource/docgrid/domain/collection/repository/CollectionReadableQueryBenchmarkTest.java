package com.opensource.docgrid.domain.collection.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

import jakarta.persistence.EntityManager;

/**
 * #312 작업3 성능 검증 — findReadableCollections의 부모 컬렉션 상속 판단을
 * "앵커 없는 재귀 CTE(변경 전)" vs "collection_closure 조인(변경 후)"로 EXPLAIN ANALYZE 비교한다.
 *
 * <p>일반 스위트에서는 제외되도록 @Tag("benchmark")를 붙였다(build.gradle의 test 태스크가 excludeTags).
 * 결과는 콘솔 + backend/build/reports/benchmark/collection-closure.txt 에 남는다.
 *
 * <pre>
 * ./backend/gradlew -p backend benchmarkTest --tests "*CollectionReadableQueryBenchmarkTest" -q \
 *   && cat backend/build/reports/benchmark/collection-closure.txt
 * </pre>
 */
@Tag("benchmark")
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("#312 작업3 — findReadableCollections 재귀 CTE vs closure table 성능 비교")
class CollectionReadableQueryBenchmarkTest {

    /** 데이터 규모별로 격차가 어떻게 벌어지는지 본다. */
    private static final int[] COLLECTION_COUNTS = {2_000, 5_000, 10_000};
    private static final int BRANCH = 8;          // 트리 분기 수 (깊이 ≈ log_BRANCH(N))
    private static final int ROLE_GRANT_COUNT = 20;
    private static final int WARMUP = 5;
    private static final int ITERATIONS = 25;

    @Autowired private CollectionRepository collectionRepository;
    @Autowired private CollectionPermissionRepository collectionPermissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private EntityManager em;

    private static final String OLD_QUERY = """
        WITH RECURSIVE collection_ancestors AS (
            SELECT id AS collection_id, id AS ancestor_id FROM collections
            UNION ALL
            SELECT ca.collection_id, c.parent_collection_id AS ancestor_id
            FROM collection_ancestors ca
            JOIN collections c ON c.id = ca.ancestor_id
            WHERE c.parent_collection_id IS NOT NULL
        ),
        readable AS (
            SELECT c.id FROM collections c WHERE c.owner_user_id = :userId AND c.status = 'ACTIVE'
            UNION
            SELECT c.id FROM collections c WHERE c.visibility = 'PUBLIC' AND c.status = 'ACTIVE'
            UNION
            SELECT c.id FROM collections c
              JOIN collection_permissions cp ON cp.collection_id = c.id
            WHERE cp.target_type = 'USER' AND cp.user_id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW()) AND c.status = 'ACTIVE'
            UNION
            SELECT c.id FROM collections c
              JOIN collection_ancestors ca ON ca.collection_id = c.id
              JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id
              JOIN user_roles ur ON ur.role_id = cp.role_id
            WHERE cp.target_type = 'ROLE' AND ur.user_id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW()) AND c.status = 'ACTIVE'
            UNION
            SELECT c.id FROM collections c
              JOIN collection_ancestors ca ON ca.collection_id = c.id
              JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id
              JOIN users u ON u.department_id = cp.department_id
            WHERE cp.target_type = 'DEPARTMENT' AND u.id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW()) AND c.status = 'ACTIVE'
        )
        SELECT c.id, c.name, COUNT(*) OVER() AS total_count
        FROM collections c
        JOIN readable r ON r.id = c.id
        JOIN users u ON u.id = c.owner_user_id
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT 20 OFFSET 0
        """;

    private static final String NEW_QUERY = """
        WITH readable AS (
            SELECT c.id FROM collections c WHERE c.owner_user_id = :userId AND c.status = 'ACTIVE'
            UNION
            SELECT c.id FROM collections c WHERE c.visibility = 'PUBLIC' AND c.status = 'ACTIVE'
            UNION
            SELECT c.id FROM collections c
              JOIN collection_permissions cp ON cp.collection_id = c.id
            WHERE cp.target_type = 'USER' AND cp.user_id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW()) AND c.status = 'ACTIVE'
            UNION
            SELECT c.id FROM collections c
              JOIN collection_closure ca ON ca.descendant_id = c.id
              JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id
              JOIN user_roles ur ON ur.role_id = cp.role_id
            WHERE cp.target_type = 'ROLE' AND ur.user_id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW()) AND c.status = 'ACTIVE'
            UNION
            SELECT c.id FROM collections c
              JOIN collection_closure ca ON ca.descendant_id = c.id
              JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id
              JOIN users u ON u.department_id = cp.department_id
            WHERE cp.target_type = 'DEPARTMENT' AND u.id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW()) AND c.status = 'ACTIVE'
        )
        SELECT c.id, c.name, COUNT(*) OVER() AS total_count
        FROM collections c
        JOIN readable r ON r.id = c.id
        JOIN users u ON u.id = c.owner_user_id
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT 20 OFFSET 0
        """;

    @Test
    @DisplayName("컬렉션 규모별 EXPLAIN ANALYZE 실행 시간 비교")
    void compareOldRecursiveCteVsClosureJoin() {
        Long ownerId = seedOwner();
        Long roleMemberId = seedRoleMember();

        // 첫 측정의 콜드 JIT 아웃라이어를 없애기 위한 전역 워밍업
        seedCollectionTree(ownerId, 500);
        backfillClosure();
        seedRoleGrants(ownerId, roleMemberId);
        em.flush();
        em.clear();
        analyzeAll();
        for (int i = 0; i < 30; i++) {
            executionTimeMs(OLD_QUERY, roleMemberId);
            executionTimeMs(NEW_QUERY, roleMemberId);
        }

        StringBuilder table = new StringBuilder();
        int lastCount = 0;
        for (int count : COLLECTION_COUNTS) {
            seedCollectionTree(ownerId, count - countExisting());
            backfillClosure();
            seedRoleGrants(ownerId, roleMemberId);
            em.flush();
            em.clear();
            analyzeAll();

            Result before = measure(OLD_QUERY, roleMemberId);
            Result after = measure(NEW_QUERY, roleMemberId);

            table.append(row(count, before, after));
            lastCount = count;
        }

        String report = """
            ========================================================================
             #312 작업3 — GET /collections 목록 쿼리: 재귀 CTE vs closure table
            ========================================================================
             PostgreSQL EXPLAIN (ANALYZE) Execution Time | warmup %d + 측정 %d회
             트리 분기 %d (깊이 ≈ log_%d N) | ROLE 권한 %d개

               컬렉션 수 │ 변경 전(재귀 CTE)         │ 변경 후(closure)          │ 개선
             ────────────┼──────────────────────────┼──────────────────────────┼────────
            %s ────────────┴──────────────────────────┴──────────────────────────┴────────
            ========================================================================

            [%,d건 · 변경 전 실행계획]
            %s
            ------------------------------------------------------------------------
            [%,d건 · 변경 후 실행계획]
            %s
            ========================================================================
            """.formatted(
                WARMUP, ITERATIONS, BRANCH, BRANCH, ROLE_GRANT_COUNT,
                table,
                lastCount, plan(OLD_QUERY, roleMemberId).strip(),
                lastCount, plan(NEW_QUERY, roleMemberId).strip());

        System.out.println(report);
        writeReportFile(report);
    }

    private void writeReportFile(String report) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of("build/reports/benchmark/collection-closure.txt");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, report);
            System.out.println("→ 리포트 저장: backend/" + out);
        } catch (java.io.IOException e) {
            System.out.println("리포트 파일 저장 실패: " + e.getMessage());
        }
    }

    private String row(int count, Result before, Result after) {
        return String.format(
            "  %,10d │ p50 %6.2f  max %6.2f ms │ p50 %6.2f  max %6.2f ms │ %5.1fx%n",
            count, before.p50, before.max, after.p50, after.max, before.p50 / after.p50);
    }

    // ---- 측정 ----

    private record Result(double p50, double avg, double max) {}

    private Result measure(String sql, Long userId) {
        for (int i = 0; i < WARMUP; i++) {
            executionTimeMs(sql, userId);
        }
        List<Double> samples = new ArrayList<>(ITERATIONS);
        for (int i = 0; i < ITERATIONS; i++) {
            samples.add(executionTimeMs(sql, userId));
        }
        Collections.sort(samples);
        double p50 = samples.get(samples.size() / 2);
        double avg = samples.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double max = samples.get(samples.size() - 1);
        return new Result(p50, avg, max);
    }

    private double executionTimeMs(String sql, Long userId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("EXPLAIN (ANALYZE) " + sql)
            .setParameter("userId", userId).getResultList();
        for (Object r : rows) {
            String line = String.valueOf(r);
            int idx = line.indexOf("Execution Time:");
            if (idx >= 0) {
                return Double.parseDouble(line.substring(idx + 15).replace("ms", "").trim());
            }
        }
        throw new IllegalStateException("Execution Time 파싱 실패");
    }

    private String plan(String sql, Long userId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sql)
            .setParameter("userId", userId).getResultList();
        StringBuilder sb = new StringBuilder();
        for (Object r : rows) {
            sb.append(r).append('\n');
        }
        return sb.toString();
    }

    private void analyzeAll() {
        for (String t : List.of("collections", "collection_closure", "collection_permissions", "user_roles", "users")) {
            em.createNativeQuery("ANALYZE " + t).executeUpdate();
        }
    }

    // ---- 시딩 ----

    private Long benchRoleId;

    private Long seedOwner() {
        return userRepository.save(User.builder()
            .email("bench-owner-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash").name("벤치 오너").status(UserStatus.ACTIVE).build()).getId();
    }

    private Long seedRoleMember() {
        User u = userRepository.save(User.builder()
            .email("bench-role-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash").name("벤치 역할 보유자").status(UserStatus.ACTIVE).build());
        Role role = roleRepository.save(Role.builder()
            .name("벤치 역할").code("BENCH-ROLE-" + UUID.randomUUID()).build());
        userRoleRepository.save(UserRole.builder().user(u).role(role).assignedAt(LocalDateTime.now()).build());
        this.benchRoleId = role.getId();
        em.flush();
        em.clear();
        return u.getId();
    }

    private long countExisting() {
        return ((Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM collections WHERE name = 'bench-col'").getSingleResult()).longValue();
    }

    /**
     * 결정적 트리를 네이티브 SQL 한 문장으로 대량 시드한다 (JPA 개별 save 없이).
     * 전체 bench 컬렉션을 id 순으로 0..N 인덱싱하고, 인덱스 i(&gt;0)의 부모를 인덱스 (i-1)/BRANCH로 둔다.
     */
    private void seedCollectionTree(Long ownerId, long addCount) {
        if (addCount <= 0) {
            return;
        }
        em.createNativeQuery("""
            INSERT INTO collections (owner_user_id, name, visibility, status, created_at, updated_at)
            SELECT :ownerId, 'bench-col', 'PRIVATE', 'ACTIVE', NOW(), NOW()
            FROM generate_series(1, :addCount)
            """)
            .setParameter("ownerId", ownerId)
            .setParameter("addCount", addCount)
            .executeUpdate();

        em.createNativeQuery("""
            WITH indexed AS (
                SELECT id, (row_number() OVER (ORDER BY id)) - 1 AS idx
                FROM collections WHERE name = 'bench-col'
            )
            UPDATE collections c
            SET parent_collection_id = parent.id
            FROM indexed child
            JOIN indexed parent ON parent.idx = (child.idx - 1) / :branch
            WHERE c.id = child.id AND child.idx > 0 AND c.parent_collection_id IS NULL
            """)
            .setParameter("branch", BRANCH)
            .executeUpdate();
    }

    private void backfillClosure() {
        em.createNativeQuery("""
            INSERT INTO collection_closure (ancestor_id, descendant_id, depth)
            WITH RECURSIVE closure AS (
                SELECT id AS ancestor_id, id AS descendant_id, 0 AS depth FROM collections
                UNION ALL
                SELECT cl.ancestor_id, c.id, cl.depth + 1
                FROM closure cl JOIN collections c ON c.parent_collection_id = cl.descendant_id
            )
            SELECT ancestor_id, descendant_id, depth FROM closure
            ON CONFLICT DO NOTHING
            """).executeUpdate();
    }

    private void seedRoleGrants(Long ownerId, Long roleMemberId) {
        // 다른 ROLE 권한(시드 마이그레이션 등)이 아니라 이 벤치마크 role의 권한만 집계해
        // ROLE 상속 경로가 항상 ROLE_GRANT_COUNT개 유지되도록 한다.
        long granted = ((Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM collection_permissions WHERE target_type = 'ROLE' AND role_id = :roleId")
            .setParameter("roleId", benchRoleId).getSingleResult()).longValue();
        long missing = ROLE_GRANT_COUNT - granted;
        if (missing <= 0) {
            return;
        }
        em.createNativeQuery("""
            INSERT INTO collection_permissions
                (collection_id, target_type, role_id, permission_type, can_read, can_write, can_admin,
                 granted_by, granted_at, created_at, updated_at)
            SELECT id, 'ROLE', :roleId, 'READ', true, false, false, :ownerId, NOW(), NOW(), NOW()
            FROM collections
            WHERE name = 'bench-col'
              AND id NOT IN (
                  SELECT collection_id FROM collection_permissions
                  WHERE target_type = 'ROLE' AND role_id = :roleId
              )
            ORDER BY md5(id::text)
            LIMIT :n
            """)
            .setParameter("roleId", benchRoleId)
            .setParameter("ownerId", ownerId)
            .setParameter("n", missing)
            .executeUpdate();
    }
}
