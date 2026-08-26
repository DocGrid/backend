-- 검색 질문·RAG 답변을 사용자별 대화방으로 묶는다.
CREATE TABLE search_conversations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id),
    title VARCHAR(160) NOT NULL,
    last_message_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_search_conversations_user_last_message
    ON search_conversations (user_id, last_message_at DESC);

ALTER TABLE search_queries ADD COLUMN conversation_id BIGINT;

-- 기존 검색은 잃지 않고 각 Query를 하나의 독립 대화로 변환한다.
INSERT INTO search_conversations (id, user_id, title, last_message_at, created_at, updated_at)
SELECT id, user_id, LEFT(query_text, 160), created_at, created_at, updated_at
FROM search_queries;

UPDATE search_queries SET conversation_id = id;

SELECT setval(
    pg_get_serial_sequence('search_conversations', 'id'),
    COALESCE((SELECT MAX(id) FROM search_conversations), 1),
    EXISTS(SELECT 1 FROM search_conversations)
);

ALTER TABLE search_queries
    ALTER COLUMN conversation_id SET NOT NULL,
    ADD CONSTRAINT fk_search_queries_conversation
        FOREIGN KEY (conversation_id) REFERENCES search_conversations (id);

CREATE INDEX idx_search_queries_conversation_created
    ON search_queries (conversation_id, created_at);
