import assert from "node:assert/strict";
import test from "node:test";

import type { SearchResponse } from "../app/lib/api-types.ts";
import { conversationIdFromSearch, conversationPath, upsertConversationTurn } from "../app/lib/conversation-history.ts";

function response(queryId: number, answer: string | null): SearchResponse {
  return {
    conversationId: 7,
    queryId,
    results: [],
    ragStatus: answer ? "SUCCESS" : "PROCESSING",
    answer,
    citations: [],
  };
}

test("대화 ID를 URL에 남기고 유효한 값만 복원한다", () => {
  assert.equal(conversationPath(7), "/search?conversationId=7");
  assert.equal(conversationPath(null), "/search");
  assert.equal(conversationIdFromSearch("?conversationId=7"), 7);
  assert.equal(conversationIdFromSearch("?conversationId=invalid"), null);
});

test("새 질문은 Turn 끝에 추가하고 같은 queryId 답변은 제자리에서 갱신한다", () => {
  const processing = upsertConversationTurn([], "첫 질문", response(10, null), "2026-08-25T10:00:00");
  const completed = upsertConversationTurn(processing, "첫 질문", response(10, "완료 답변"), "2026-08-25T10:00:00");
  const next = upsertConversationTurn(completed, "후속 질문", response(11, null), "2026-08-25T10:01:00");

  assert.equal(completed.length, 1);
  assert.equal(completed[0].response.answer, "완료 답변");
  assert.deepEqual(next.map((turn) => turn.queryId), [10, 11]);
});
