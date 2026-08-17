-- 검색-RAG 비동기 처리 전환: RagResponse를 PROCESSING 상태로 먼저 저장할 때는 아직 답변이 없다.
ALTER TABLE rag_responses ALTER COLUMN answer_text DROP NOT NULL;
