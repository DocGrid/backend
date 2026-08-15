import assert from "node:assert/strict";
import test from "node:test";

import type { SearchResponse } from "../app/lib/api-types.ts";
import { groupSearchSources } from "../app/lib/search-sources.ts";

test("groups multiple citation chunks from the same document into one source", () => {
  const response: SearchResponse = {
    queryId: 11,
    answer: "요약",
    results: [
      { rank: 1, documentId: 12, chunkId: 101, documentTitle: "12기 코테이토 회칙", chunkText: "첫 근거", pageNo: 1, similarityScore: 0.551 },
      { rank: 2, documentId: 12, chunkId: 102, documentTitle: "12기 코테이토 회칙", chunkText: "둘째 근거", pageNo: 2, similarityScore: 0.492 },
    ],
    citations: [
      { label: "[1]", documentId: 12, documentTitle: "12기 코테이토 회칙", chunkId: 101, pageNo: 1, quotedText: "첫 근거" },
      { label: "[2]", documentId: 12, documentTitle: "12기 코테이토 회칙", chunkId: 102, pageNo: 2, quotedText: "둘째 근거" },
    ],
  };

  assert.deepEqual(groupSearchSources(response), [{
    documentId: 12,
    documentTitle: "12기 코테이토 회칙",
    labels: ["[1]", "[2]"],
    excerpts: ["첫 근거", "둘째 근거"],
    pages: [1, 2],
    chunkCount: 2,
    similarityScore: 0.551,
  }]);
});

test("deduplicates repeated citations for one chunk", () => {
  const response: SearchResponse = {
    queryId: 12,
    answer: null,
    results: [
      { rank: 1, documentId: 7, chunkId: 70, documentTitle: "운영 가이드", chunkText: "근거", pageNo: null, similarityScore: 0.8 },
    ],
    citations: [
      { label: "[1]", documentId: 7, documentTitle: "운영 가이드", chunkId: 70, pageNo: null, quotedText: "근거" },
      { label: "[1]", documentId: 7, documentTitle: "운영 가이드", chunkId: 70, pageNo: null, quotedText: "근거" },
    ],
  };

  const [source] = groupSearchSources(response);
  assert.equal(source.chunkCount, 1);
  assert.deepEqual(source.excerpts, ["근거"]);
});

test("groups raw search results when citations are unavailable", () => {
  const response: SearchResponse = {
    queryId: 13,
    answer: null,
    results: [
      { rank: 1, documentId: 3, chunkId: 31, documentTitle: "회의록", chunkText: "일정", pageNo: null, similarityScore: 0.7 },
      { rank: 2, documentId: 3, chunkId: 32, documentTitle: "회의록", chunkText: "참석자", pageNo: null, similarityScore: 0.6 },
      { rank: 3, documentId: 4, chunkId: 41, documentTitle: "규정", chunkText: "규정 내용", pageNo: 4, similarityScore: 0.5 },
    ],
    citations: [],
  };

  const sources = groupSearchSources(response);
  assert.equal(sources.length, 2);
  assert.deepEqual(sources[0].labels, ["[1]", "[2]"]);
  assert.equal(sources[0].chunkCount, 2);
  assert.equal(sources[1].documentId, 4);
});
