-- collections: 문서 컬렉션(그룹/폴더/워크스페이스) 테이블
-- (DocumentCollection 엔티티 대응. java.util.Collection과의 이름 충돌을 피해 엔티티명만 DocumentCollection, 테이블명은 collections)
CREATE TABLE collections (
    id BIGSERIAL PRIMARY KEY,

    -- 컬렉션 소유자 (-> users.id)
    owner_user_id BIGINT NOT NULL REFERENCES users (id),

    -- 상위 컬렉션 self-FK, 최상위 컬렉션은 NULL
    parent_collection_id BIGINT REFERENCES collections (id),

    name VARCHAR(255) NOT NULL,
    description TEXT,
    visibility VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    deleted_at TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_collections_owner_user_id ON collections (owner_user_id);
CREATE INDEX idx_collections_parent_collection_id ON collections (parent_collection_id);
CREATE INDEX idx_collections_visibility ON collections (visibility);
CREATE INDEX idx_collections_status ON collections (status);
