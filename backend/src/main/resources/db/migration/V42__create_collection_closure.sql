-- collection_closure: 컬렉션 트리의 조상-자손 관계를 물질화한 closure table.
--
-- 목적: findReadableCollections 등에서 매 조회마다 앵커 없는 재귀 CTE로 전체 컬렉션의
-- 조상 클로저를 계산하던 것을, 인덱스 조인 1회로 대체한다.
-- 규칙: 모든 컬렉션은 자기 자신에 대한 행(depth 0)을 가진다. DELETED 컬렉션도 포함한다
-- (재귀 CTE 기반 findAncestorIdsInclusive와 동일한 의미 — 삭제된 조상도 체인에 남긴다).
-- 유지: 컬렉션 생성/삭제 시 CollectionCommandService가 갱신한다. 부모 이동 API는 현재 없다.
CREATE TABLE collection_closure (
    ancestor_id   BIGINT NOT NULL REFERENCES collections (id),
    descendant_id BIGINT NOT NULL REFERENCES collections (id),
    depth         INT    NOT NULL,
    PRIMARY KEY (ancestor_id, descendant_id)
);

CREATE INDEX idx_collection_closure_descendant ON collection_closure (descendant_id);

-- 기존 트리 backfill (1회) — 자기 자신(depth 0) + 모든 조상.
INSERT INTO collection_closure (ancestor_id, descendant_id, depth)
WITH RECURSIVE closure AS (
    SELECT id AS ancestor_id, id AS descendant_id, 0 AS depth
    FROM collections
    UNION ALL
    SELECT cl.ancestor_id, c.id AS descendant_id, cl.depth + 1
    FROM closure cl
    JOIN collections c ON c.parent_collection_id = cl.descendant_id
)
SELECT ancestor_id, descendant_id, depth FROM closure;
