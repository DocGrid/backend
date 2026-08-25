import assert from "node:assert/strict";
import test from "node:test";

import type { SyncIssueAdmin } from "../app/lib/api-types.ts";
import { parseSyncEvidence, syncEventStatusLabel, syncIssueAction, syncIssueStatusLabel, syncIssueTitle } from "../app/lib/sync-operations.ts";

function issue(overrides: Partial<SyncIssueAdmin> = {}): SyncIssueAdmin {
  return {
    issueId: 1,
    issueKey: "MISSING_EMBEDDINGS:VERSION:1:MODEL:1",
    issueType: "MISSING_EMBEDDINGS",
    severity: "CRITICAL",
    status: "OPEN",
    documentId: 1,
    documentVersionId: 1,
    embeddingModelId: 1,
    expectedJson: '{"activeEmbeddingCount":3}',
    actualJson: '{"activeEmbeddingCount":1,"currentModelEmbeddingCount":3,"allModelEmbeddingCount":3}',
    repairable: false,
    detectedAt: "2026-08-25T10:00:00",
    lastDetectedAt: "2026-08-25T10:00:00",
    repairEventId: null,
    repairAttemptCount: 0,
    resolvedAt: null,
    resolutionMessage: null,
    ...overrides,
  };
}

test("정합성 코드와 상태를 운영자가 이해할 수 있는 문구로 바꾼다", () => {
  assert.equal(syncIssueTitle("MISSING_EMBEDDINGS"), "검색 벡터가 일부 부족함");
  assert.equal(syncIssueStatusLabel("OPEN"), "조치 필요");
  assert.equal(syncEventStatusLabel("FAILED"), "최종 실패");
});

test("비활성 Vector 때문에 자동 복구할 수 없는 이유를 수치와 함께 설명한다", () => {
  assert.equal(syncIssueAction(issue()), "현재 모델 Vector 3개 중 1개만 활성 상태라 자동 재색인하지 않습니다. 비활성 Vector의 원인을 먼저 확인하세요.");
});

test("복구 가능한 Issue에는 안전 복구가 만드는 결과를 설명한다", () => {
  assert.equal(syncIssueAction(issue({ repairable: true })), "안전 복구를 요청하면 기존 처리 흐름으로 재색인 Event를 생성합니다.");
});

test("기대값과 실제값 JSON을 읽기 쉬운 항목으로 변환한다", () => {
  assert.deepEqual(parseSyncEvidence('{"chunkCount":3,"liveJob":false}'), [
    { label: "Chunk 수", value: "3" },
    { label: "실행 중 Job", value: "없음" },
  ]);
});
