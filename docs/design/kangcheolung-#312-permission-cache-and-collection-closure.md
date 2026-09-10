# #312 권한 캐시 정합성 및 컬렉션 조회 성능 개선 (3건)

closes #312

---

## 배경

권한 도메인(entity ~ service ~ 검색 연동)을 레이어별로 리뷰하면서 발견한 정합성 1건 +
코드 품질/성능 2건을 한 이슈로 묶어 처리한다. 셋 다 `PermissionQueryService`를 중심으로 한
권한 판정·컬렉션 조회 경로에 있고, `#240`(앱단 권한 필터링을 SQL로 이관) 이후 후속 정리다.

| 작업 | 성격 | 파일 |
|---|---|---|
| 1. 컬렉션 문서 추가 시 USER 접근 캐시 누락 | 🐞 정합성 버그 | `CollectionCommandService` |
| 2. `PermissionQueryService` 수동 계측 코드 제거 | ♻️ 코드 품질 | `PermissionQueryService` |
| 3. 컬렉션 조상 조회 재귀 CTE → closure table | ♻️ 성능 | `CollectionRepository`, `DocumentRepository`, `CollectionCommandService`, 신규 마이그레이션 |

---

## 작업 1 — 컬렉션에 문서 추가 시 USER 권한 보유자의 접근 캐시가 갱신되지 않음

### 문제

`CollectionCommandService.addDocument()`가 `collection_documents` 매핑만 저장하고, 그 컬렉션에
부여된 **USER 대상 권한** 보유자의 `user_document_access_cache`를 갱신하지 않았다.

컬렉션의 USER 대상 권한은 **오직 이 캐시로만 판정된다** — `PermissionQueryService.canReadDocument`
4·5·6단계(ROLE live / DEPT live / 부모 컬렉션 상속)에 컬렉션 USER 경로가 없다. 검색 pre-filter
(`DocumentRepository.findReadableDocumentIds*`)도 캐시 UNION 브랜치에만 의존한다.

재현:
```
1. 컬렉션 X에 문서 1,2,3이 속함
2. 관리자가 Bob에게 컬렉션 X READ 부여 → 캐시에 (Bob,1),(Bob,2),(Bob,3) 생성
3. 관리자가 X에 문서 4 추가 → collection_documents에 (X,4)만. 캐시 그대로.
4. Bob이 문서 4 조회 → canReadDocument 1~6단계 전부 false → 403
5. Bob의 검색 결과에도 문서 4가 안 나옴
```

`removeDocument()`는 이미 반대 동작(`cacheService.bulkRevokeBySourcesForDocument`)을 하고 있어
`addDocument()`에만 대칭 동작이 빠져 있었다.

### 해결

`addDocument()`에서 매핑 저장 직후, 이 컬렉션의 **만료되지 않은 USER 대상 권한**을 조회해 방금
추가한 문서의 캐시 행을 `cacheService.grantUserPermission(...)`으로 생성한다
(source_type = `DIRECT_COLLECTION_PERMISSION`, source_id = 권한 id).

```java
private void grantUserAccessCachesForAddedDocument(Long collectionId, Document document) {
    LocalDateTime now = LocalDateTime.now();
    collectionPermissionRepository.findAllByCollectionId(collectionId).stream()
            .filter(p -> p.getTargetType() == PermissionTargetType.USER)
            .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(now))
            .forEach(p -> cacheService.grantUserPermission(
                    p.getUser(), document,
                    p.isCanRead(), p.isCanWrite(), p.isCanAdmin(),
                    AccessSourceType.DIRECT_COLLECTION_PERMISSION, p.getId(), p.getExpiresAt()));
}
```

근본적으로는 컬렉션-문서 매핑 변경도 `PermissionCacheRefreshSyncEventHandler`처럼 Outbox 이벤트로
재투영하는 게 정석이나, 이번 변경은 `removeDocument`와 동일한 방식(동기 직접 호출)으로 대칭만 맞췄다.
Outbox 확장은 별도.

---

## 작업 2 — PermissionQueryService 수동 계측 코드 제거

### 문제

`canReadDocument` / `canWriteDocument` / `canAdminDocument` / `checkDocumentPermission`에 단계별
`System.nanoTime()` 계측과 `log.info`가 판정 로직보다 많은 비중으로 남아 있었다.

```java
long t2 = System.nanoTime();
double step1Ms = (t2 - t1) / 1_000_000.0;
if (document.getVisibility() == VisibilityType.PUBLIC) {
    log.info("[PERM] canRead public=true doc={} user={} step1={}ms elapsed={}ms", ...);
    return true;
}
```

- `canReadDocument` 하나가 60여 줄인데 실질 로직은 조건 분기 6개
- 판정이 **성공할 때마다** `log.info` → 문서 목록 20건 조회 시 `[PERM]` 로그 20줄
- 일회성 프로파일링 목적이었고 목적은 이미 달성됨. `java-style.md`상 단순 성공 경로는 로그 생략

### 해결

4개 메서드에서 `System.nanoTime()` 변수·`log.info`·`ms()` 헬퍼·`@Slf4j`를 제거했다.
**판정 로직·분기 순서·반환값은 변경하지 않았다**(순수 삭제). 각 메서드가 단계 주석 + 조건 분기만
남아 절반 이하로 축소됐다.

계측이 필요해지면 Micrometer `@Timed`로 대체 — 모니터링 스택 도입 이슈와 함께 결정한다.

---

## 작업 3 — 컬렉션 조상 조회 재귀 CTE를 closure table로 대체

### 문제

목록/검색 pre-filter 쿼리가 쓰던 `collection_ancestors` 재귀 CTE는 **앵커(시작 조건)가 없어**
매 호출마다 `collections` 전체를 시작점으로 조상 클로저를 계산했다.

```sql
WITH RECURSIVE collection_ancestors AS (
    SELECT id AS collection_id, id AS ancestor_id FROM collections   -- WHERE 없음 = 전체
    UNION ALL ...
)
```

- 중간 결과 ≈ (전체 컬렉션 수) × (평균 트리 깊이). 컬렉션 1만 개 / 깊이 5면 목록 조회당 ≈ 5만 행
  생성 후 페이지 20행만 사용
- `GET /collections`, count 폴백, 검색 pre-filter 2종에서 호출 — 목록 화면을 열 때마다 발생
- `EXPLAIN`에 앵커가 `Seq Scan on collections`로 잡힘
- 대조: `findReadableChildren`·`findAncestorIdsInclusive`는 `WHERE id = :id` 앵커가 있어 가볍다

### 해결: closure table

조상-자손 관계를 물질화한 테이블을 두고, 조회는 인덱스 조인, 유지는 트리 변경 시에만 한다.

`V42__create_collection_closure.sql`:
```sql
CREATE TABLE collection_closure (
    ancestor_id   BIGINT NOT NULL REFERENCES collections (id),
    descendant_id BIGINT NOT NULL REFERENCES collections (id),
    depth         INT    NOT NULL,          -- 0 = 자기 자신
    PRIMARY KEY (ancestor_id, descendant_id)
);
CREATE INDEX idx_collection_closure_descendant ON collection_closure (descendant_id);
-- 기존 트리 backfill (재귀 CTE 1회, DELETED 컬렉션 포함)
```

- **조회**: 재귀 CTE 제거 →
  `JOIN collection_closure ca ON ca.descendant_id = c.id` +
  `JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id`
  - `CollectionRepository.findReadableCollections`
  - `CollectionRepository.countReadableCollections`
  - `DocumentRepository.findReadableDocumentIds`
  - `DocumentRepository.findReadableDocumentIdsInCollection`
- **유지** (`CollectionCommandService`):
  - `createCollection` → `insertClosureForNewCollection(newId, parentId)`
    (부모의 조상 전체를 depth+1로 상속 + 자기 자신 depth 0. parentId=null이면 자기 자신만)
  - `deleteCollection` → `deleteClosureByDescendantIds(targetIds)`
    (cascade 서브트리 전체가 함께 삭제되므로 descendant 기준 삭제로 충분)
- 두 `@Modifying` 쿼리는 `flushAutomatically = true` — 직전 영속 변경(엔티티 저장/삭제)을 먼저 반영

부모 이동 API는 현재 없어 순환 참조가 API상 불가능하다. 생기면 서브트리 재계산 로직이 필요하다(별도).
`findReadableChildren` / `findAncestorIdsInclusive` / `findEffectiveCollectionIdsForDocument`는
앵커가 있어 성능 문제가 없으므로 이번 범위에서 재귀 CTE를 유지했다.

---

## 테스트

| 대상 | 테스트 |
|---|---|
| 작업 1 | `CollectionCommandServiceTest` — USER 권한 → 캐시 생성 / ROLE·만료 권한 → 캐시 생성 안 함 |
| 작업 2 | 기존 `PermissionQueryServiceTest` 수정 없이 통과 (판정 결과 불변) |
| 작업 3 | `CollectionTreeRepositoryTest` — `insertClosureForNewCollection`이 자기 자신(depth 0)+조상을 depth와 함께 넣음, `deleteClosureByDescendantIds`가 서브트리 행 제거. 기존 상속 테스트(`findReadableCollections`, `findReadableDocumentIds`)는 테스트 헬퍼가 운영 코드와 동일하게 closure를 갱신하도록 수정 후 통과 |

`./backend/gradlew -p backend test` — 권한/컬렉션/문서 서비스·검색 관련 스위트 전부 통과.
(embedding 통합 테스트는 로컬 Redis/임베딩 서버 의존이라 이 변경과 무관하게 환경에 따라 스킵/실패)

### 후속

- `#240` 설계 문서(`docs/design/kangcheolung-#240-collection-list-pagination.md`)의 성능 개선
  후속편. 부하 측정(컬렉션 5,000건 시드 → `GET /collections` p95 before/after)은 포트폴리오
  정리 시 별도로 첨부.
