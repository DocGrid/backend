import type { SearchConversationTurn, SearchResponse } from "./api-types";

export function conversationPath(conversationId: number | null): string {
  return conversationId ? `/search?conversationId=${conversationId}` : "/search";
}

export function conversationIdFromSearch(search: string): number | null {
  const value = Number(new URLSearchParams(search).get("conversationId"));
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}

export function upsertConversationTurn(
  turns: SearchConversationTurn[],
  queryText: string,
  response: SearchResponse,
  createdAt: string,
): SearchConversationTurn[] {
  const existingIndex = turns.findIndex((turn) => turn.queryId === response.queryId);
  const nextTurn = { queryId: response.queryId, queryText, createdAt, response };
  if (existingIndex < 0) return [...turns, nextTurn];
  return turns.map((turn, index) => index === existingIndex ? nextTurn : turn);
}
