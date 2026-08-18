import assert from "node:assert/strict";
import test from "node:test";

import type { DocumentStatus } from "../app/lib/api-types.ts";
import { isDocumentProcessing } from "../app/lib/document-status.ts";

function status(overrides: Partial<DocumentStatus>): DocumentStatus {
  return {
    documentId: 14,
    documentStatus: "INDEXED",
    currentVersion: { versionNo: 1, status: "INDEXED" },
    processingVersion: null,
    ...overrides,
  };
}

test("최초 인덱싱 중에는 처리 중으로 판단해 상태를 다시 읽는다", () => {
  assert.equal(isDocumentProcessing(status({
    documentStatus: "INDEXING",
    currentVersion: null,
    processingVersion: { versionNo: 1, status: "PARSING", jobStatus: "PROCESSING" },
  })), true);
});

test("재인덱싱은 문서가 INDEXED를 유지해도 처리 중으로 판단한다", () => {
  // 문서 상태만 보면 완료와 구분되지 않으므로 processingVersion을 기준으로 삼는다.
  assert.equal(isDocumentProcessing(status({
    documentStatus: "INDEXED",
    processingVersion: { versionNo: 2, status: "EMBEDDING", jobStatus: "PROCESSING" },
  })), true);
});

test("인덱싱이 끝나면 처리 중이 아니므로 재조회를 멈춘다", () => {
  assert.equal(isDocumentProcessing(status({
    documentStatus: "INDEXED",
    currentVersion: { versionNo: 2, status: "INDEXED" },
  })), false);
});

test("인덱싱이 실패해도 처리 중 버전이 사라지므로 재조회를 멈춘다", () => {
  // 백엔드가 FAILED Version은 processingVersion으로 반환하지 않아 무한 재조회가 생기지 않는다.
  assert.equal(isDocumentProcessing(status({ documentStatus: "FAILED" })), false);
});

test("상태를 아직 못 읽은 경우에는 재조회하지 않는다", () => {
  assert.equal(isDocumentProcessing(null), false);
});
